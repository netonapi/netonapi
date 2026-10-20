package logic

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logic.protocol.GeminiThoughtSignatures
import logic.protocol.StreamStage
import neton.redis.RedisClient
import neton.security.random.SecureRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Gemini thought signature 的跨请求回传（neton-docs spec/ai.md §4.5.1 机制 B；gateway 文档 §4.6）。
 *
 * 问题：客户端用 OpenAI / Anthropic 格式时，下一轮请求里的 assistant 历史是客户端自己存的，
 * Gemini 的签名不在里面；而 Gemini 3 在当前轮缺签名会直接 400。
 *
 * 做法：Gemini 响应里每个带签名的 functionCall，网关生成一个**安全随机、不可猜测**的状态引用，
 * 替换返回给客户端的工具调用 ID（各协议的客户端都会原样带回工具调用 ID）；签名存进存储，
 * 记录绑定令牌、用户、上游类型、上游模型和调用内容摘要。下一轮按引用取回并逐项校验，任一不符即视为缺失。
 *
 * 不把签名塞进 Anthropic thinking 的 signature 或 Responses 的 encrypted_content：
 * 那些是其他厂商的原生字段，会被它们校验。
 */
@Serializable
data class SignatureRecord(
    val signature: String,
    val originalId: String,
    val tokenId: Long,
    val userId: Long,
    /** 上游类型（gemini / vertex）：不同来源的签名不混用。 */
    val upstreamType: String,
    /** 上游模型：签名属于产生它的那个模型。 */
    val upstreamModel: String,
    val channelId: Long,
    /** 函数名 + 规范化参数的 SHA-256。 */
    val callDigest: String,
)

/** 签名存储。必须有过期时间与容量约束（spec §4.5.1）。 */
interface ThoughtSignatureStore {
    suspend fun put(ref: String, record: SignatureRecord, ttl: Duration)
    suspend fun get(ref: String): SignatureRecord?
}

/** Redis 实现：多实例共享；容量由 TTL 与 Redis 的 maxmemory 策略约束。 */
class RedisThoughtSignatureStore(private val redis: RedisClient) : ThoughtSignatureStore {
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun put(ref: String, record: SignatureRecord, ttl: Duration) {
        redis.set(KEY_PREFIX + ref, json.encodeToString(SignatureRecord.serializer(), record), ttl)
    }
    override suspend fun get(ref: String): SignatureRecord? =
        redis.getValue(KEY_PREFIX + ref)?.let { runCatching { json.decodeFromString(SignatureRecord.serializer(), it) }.getOrNull() }
    private companion object { const val KEY_PREFIX = "gateway:gsig:" }
}

/**
 * 进程内实现：只给没配 Redis 的单实例部署用（与 RateLimiter 的退化方式一致，启动时会打警告）。
 * 有容量上限，满了淘汰最早写入的；过期按写入时间判断。
 */
class InMemoryThoughtSignatureStore(
    private val capacity: Int = 100_000,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : ThoughtSignatureStore {
    private class Entry(val record: SignatureRecord, val expiresAt: Long)
    private val mutex = Mutex()
    private val map = LinkedHashMap<String, Entry>()

    override suspend fun put(ref: String, record: SignatureRecord, ttl: Duration) = mutex.withLock {
        map.remove(ref)
        while (map.size >= capacity) map.remove(map.keys.first())
        map[ref] = Entry(record, now() + ttl.inWholeMilliseconds)
    }

    override suspend fun get(ref: String): SignatureRecord? = mutex.withLock {
        val e = map[ref] ?: return@withLock null
        if (e.expiresAt <= now()) { map.remove(ref); null } else e.record
    }
}

/** 进程级的签名存储与开关，由 GatewayRuntimeBootstrap 装配。 */
object ThoughtSignatureVaults {
    /** 默认进程内；配了 Redis 时由 bootstrap 换成 Redis 实现。 */
    var store: ThoughtSignatureStore = InMemoryThoughtSignatureStore()
    var ttl: Duration = 24.hours
    /** 给 OpenAI 格式客户端输出官方 `extra_content.google.thought_signature`（spec §4.5.1 机制 A），默认开启。 */
    var echoToOpenAiClients: Boolean = true
}

/**
 * 一次中转请求的签名状态（RelayEngine 每请求一个实例）。
 *
 * 响应方向的改写在同步的转换函数里进行，只生成引用、记下待写入的记录；
 * RelayEngine 在把响应写给客户端**之前**调用 [flush] 落库——客户端拿到 ID 时记录一定已存在。
 */
class ThoughtSignatureVault(
    private val binding: Binding,
    private val store: ThoughtSignatureStore = ThoughtSignatureVaults.store,
    private val ttl: Duration = ThoughtSignatureVaults.ttl,
    private val keepExtraContent: Boolean = true,
) {
    data class Binding(
        val tokenId: Long,
        val userId: Long,
        val upstreamType: String,
        val upstreamModel: String,
        val channelId: Long,
    )

    private val json = Json { }
    private val pending = mutableListOf<Pair<String, SignatureRecord>>()

    // ---------------- 请求方向：引用 → 签名 ----------------

    /**
     * 在 hub 请求里，把 assistant 历史中由本网关签发的状态引用换回签名
     * （写到 tool call 的 `extra_content.google.thought_signature`，由 OpenAiToGeminiRequest 写成 thoughtSignature）。
     * 客户端自己带回了签名（机制 A）的保持不动。校验不通过的引用当作没有签名。
     */
    suspend fun resolve(hubBody: String): String {
        val root = json.parseToJsonElement(hubBody).jsonObject
        val messages = root["messages"] as? JsonArray ?: return hubBody
        var changed = false
        val out = messages.map { m ->
            val msg = m as? JsonObject ?: return@map m
            if (msg["role"]?.jsonPrimitive?.contentOrNull != "assistant") return@map m
            val calls = msg["tool_calls"] as? JsonArray ?: return@map m
            val newCalls = calls.map { c ->
                val call = c as? JsonObject ?: return@map c
                if (GeminiThoughtSignatures.fromToolCall(call) != null) return@map c
                val id = call["id"]?.jsonPrimitive?.contentOrNull ?: return@map c
                val ref = refOf(id) ?: return@map c
                val record = runCatching { store.get(ref) }.getOrNull() ?: return@map c
                if (!matches(record, call)) return@map c
                changed = true
                GeminiThoughtSignatures.withSignature(call, record.signature)
            }
            JsonObject(msg + ("tool_calls" to JsonArray(newCalls)))
        }
        return if (changed) JsonObject(root + ("messages" to JsonArray(out))).toString() else hubBody
    }

    private fun matches(r: SignatureRecord, call: JsonObject): Boolean {
        val fn = call["function"] as? JsonObject ?: return false
        val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return false
        val args = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
        return r.tokenId == binding.tokenId &&
            r.userId == binding.userId &&
            r.upstreamType == binding.upstreamType &&
            r.upstreamModel == binding.upstreamModel &&
            r.callDigest == GeminiThoughtSignatures.callDigest(name, args)
    }

    // ---------------- 响应方向：签名 → 引用 ----------------

    /** 非流式 hub 响应：给每个带签名的 tool call 换上状态引用 ID。 */
    fun issue(hubResponse: String): String {
        val root = runCatching { json.parseToJsonElement(hubResponse).jsonObject }.getOrNull() ?: return hubResponse
        val choices = root["choices"] as? JsonArray ?: return hubResponse
        var changed = false
        val newChoices = choices.map { ch ->
            val choice = ch as? JsonObject ?: return@map ch
            val message = choice["message"] as? JsonObject ?: return@map ch
            val calls = message["tool_calls"] as? JsonArray ?: return@map ch
            val newCalls = calls.map { c -> (c as? JsonObject)?.let { rewriteCall(it) { changed = true } } ?: c }
            JsonObject(choice + ("message" to JsonObject(message + ("tool_calls" to JsonArray(newCalls)))))
        }
        return if (changed) JsonObject(root + ("choices" to JsonArray(newChoices))).toString() else hubResponse
    }

    /**
     * 流式 hub 阶段：GeminiToOpenAiStream 把完整的 functionCall 一次放进一个 chunk，
     * 所以逐个 SSE 事件改写 delta.tool_calls 即可。
     */
    fun stage(): StreamStage {
        val buffer = StringBuilder()
        fun process(text: String, flush: Boolean): String {
            buffer.append(text)
            val out = StringBuilder()
            while (true) {
                val end = buffer.indexOf("\n\n")
                if (end < 0) break
                val event = buffer.substring(0, end + 2)
                buffer.deleteRange(0, end + 2)
                out.append(rewriteEvent(event))
            }
            if (flush && buffer.isNotEmpty()) { out.append(buffer); buffer.clear() }
            return out.toString()
        }
        return StreamStage({ process(it, flush = false) }, { process("", flush = true) })
    }

    private fun rewriteEvent(event: String): String {
        val line = event.trimEnd()
        if (!line.startsWith("data: ")) return event
        val data = line.removePrefix("data: ")
        if (data == "[DONE]" || !data.contains("thought_signature")) return event
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return event
        val choices = root["choices"] as? JsonArray ?: return event
        var changed = false
        val newChoices = choices.map { ch ->
            val choice = ch as? JsonObject ?: return@map ch
            val delta = choice["delta"] as? JsonObject ?: return@map ch
            val calls = delta["tool_calls"] as? JsonArray ?: return@map ch
            val newCalls = calls.map { c -> (c as? JsonObject)?.let { rewriteCall(it) { changed = true } } ?: c }
            JsonObject(choice + ("delta" to JsonObject(delta + ("tool_calls" to JsonArray(newCalls)))))
        }
        if (!changed) return event
        return "data: " + JsonObject(root + ("choices" to JsonArray(newChoices))).toString() + "\n\n"
    }

    private fun rewriteCall(call: JsonObject, onChange: () -> Unit): JsonObject {
        val signature = GeminiThoughtSignatures.fromToolCall(call) ?: return call
        val fn = call["function"] as? JsonObject ?: return call
        val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return call
        val args = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
        val ref = SecureRandom.string(REF_LENGTH, REF_ALPHABET)
        pending += ref to SignatureRecord(
            signature = signature,
            originalId = call["id"]?.jsonPrimitive?.contentOrNull ?: "",
            tokenId = binding.tokenId, userId = binding.userId,
            upstreamType = binding.upstreamType, upstreamModel = binding.upstreamModel,
            channelId = binding.channelId,
            callDigest = GeminiThoughtSignatures.callDigest(name, args),
        )
        onChange()
        val withRef = JsonObject(call + ("id" to JsonPrimitive(ID_PREFIX + ref)))
        return if (keepExtraContent) withRef else GeminiThoughtSignatures.withoutSignature(withRef)
    }

    /** 把本次签发的记录写入存储。必须在响应写给客户端之前调用。 */
    suspend fun flush() {
        if (pending.isEmpty()) return
        val batch = pending.toList(); pending.clear()
        for ((ref, record) in batch) store.put(ref, record, ttl)
    }

    companion object {
        /** 工具调用 ID 前缀。引用部分只含 [A-Za-z0-9]，满足 OpenAI / Anthropic / Gemini 对 ID 的字符集要求。 */
        const val ID_PREFIX = "call_ngs_"
        const val REF_LENGTH = 32
        private const val REF_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        /** 从工具调用 ID 里取出状态引用；不是本网关签发的格式返回 null。 */
        fun refOf(id: String): String? {
            if (!id.startsWith(ID_PREFIX)) return null
            val ref = id.removePrefix(ID_PREFIX)
            return ref.takeIf { it.length == REF_LENGTH && it.all { c -> c.isLetterOrDigit() && c.code < 128 } }
        }
    }
}

/**
 * 占位兼容的产品开关（neton-docs spec/ai.md §4.5.2）。默认开启，但只在渠道与模型有有效验证记录时实际生效；
 * 环境变量 NETONAPI_GEMINI_SIGNATURE_BYPASS=0 可整体关闭。
 */
object ThoughtSignatureBypassPolicy {
    var enabled: Boolean = true
}
