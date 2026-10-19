package logic

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonPrimitive
import logic.protocol.GeminiThoughtSignatures
import model.Channel
import model.ChannelVerification
import neton.core.http.HttpHeaders
import neton.database.dsl.*
import neton.http.client.HttpClientBody
import neton.http.client.HttpClientError
import neton.http.client.HttpClientException
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest
import neton.logging.Logger
import table.ChannelVerificationTable

/**
 * 渠道能力在真实上游上的验证（V016；neton-docs spec/ai.md §4.5.2 的启用闸门）。
 *
 * 只有「通过、且未过期」的记录才让对应能力生效。没有记录、记录失败或过期，一律视为未验证：
 * 兼容降级不能靠一句「发布前测过」放行。
 */
@neton.core.annotations.Logic(logger = "logic.gateway-capability-verify")
class ChannelVerificationLogic(private val log: Logger, private val channelLogic: ChannelLogic) {

    /** 判定：该渠道 + 能力 + 上游模型是否有有效的通过记录。结果在进程内缓存 [CACHE_MS]。 */
    suspend fun isVerified(channelId: Long, capability: String, model: String): Boolean {
        val key = "$channelId|$capability|$model"
        val now = now()
        cacheMutex.withLock { cache[key]?.let { (value, at) -> if (now - at < CACHE_MS) return value } }
        val record = runCatching { find(channelId, capability, model) }.getOrNull()
        val value = record != null && record.ok == 1 && now - record.verifiedAt < VALIDITY_MS
        cacheMutex.withLock { cache[key] = value to now }
        return value
    }

    suspend fun record(channelId: Long, capability: String, model: String, ok: Boolean, detail: String?) {
        val existing = find(channelId, capability, model)
        val row = ChannelVerification(
            id = existing?.id ?: 0, channelId = channelId, capability = capability, model = model,
            ok = if (ok) 1 else 0, verifiedAt = now(), detail = detail?.take(512),
        )
        if (existing == null) ChannelVerificationTable.insert(row) else ChannelVerificationTable.update(row)
        cacheMutex.withLock { cache.remove("$channelId|$capability|$model") }
    }

    private suspend fun find(channelId: Long, capability: String, model: String): ChannelVerification? =
        ChannelVerificationTable.query {
            where {
                and(
                    ChannelVerification::channelId eq channelId,
                    ChannelVerification::capability eq capability,
                    ChannelVerification::model eq model,
                )
            }
        }.list().firstOrNull()

    /** 定时任务入口：验证全部启用中的 Gemini 渠道的每个模型。返回验证的（渠道, 模型）数。 */
    suspend fun verifyAllGeminiSignatureBypass(): Int {
        val targets = channelLogic.listEnabled()
            .filter { it.type in GEMINI_TYPES }
            .flatMap { ch -> ch.models.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { ch to it } }
        val gate = Semaphore(4)
        coroutineScope {
            targets.map { (ch, model) -> async { gate.withPermit { verifyGeminiSignatureBypass(ch, model) } } }.awaitAll()
        }
        return targets.size
    }

    /**
     * 向真实上游发一次「带官方占位签名的两轮工具调用」：上游接受（2xx）即通过。
     * 这正是中转时会发出的形状，所以通过就说明该渠道与模型确实接受占位值。
     */
    suspend fun verifyGeminiSignatureBypass(channel: Channel, requestedModel: String): Boolean {
        val upstream = RelayEngine.adapterForChannel(channel.type) ?: return false
        val mapped = RequestRewriter.rewrite(
            """{"model":${JsonPrimitive(requestedModel)},"messages":[{"role":"user","content":"ping"}],"max_tokens":16}""",
            channel, injectStreamOptions = false,
        )
        val upstreamModel = mapped.upstreamModel
        val result = runCatching {
            val key = channelLogic.pickKey(channel.id) ?: error("channel has no available key")
            val body = upstream.upstreamBody(probeBody(), upstreamModel, false)
            val path = RelayEngine.upstreamPathFor(upstream, channel, "/v1/chat/completions", upstreamModel, stream = false)
            val url = channel.baseUrl.trimEnd('/') + path
            val headers = upstream.requestHeaders(key.apiKey, "POST", url, body, channel)
            val res = RelayEngine.clientFor(channel).request(HttpClientRequest(
                method = HttpClientMethod.Post, url = url,
                headers = HttpHeaders.from(headers), body = HttpClientBody.Json(body),
            ))
            (res.statusCode in 200..299) to (if (res.statusCode in 200..299) null else "HTTP ${res.statusCode}: ${res.body.take(400)}")
        }.getOrElse { e ->
            val detail = when (e) {
                is HttpClientException -> NetGuard.rejectionCode(e.error)
                    ?: (e.error as? HttpClientError.Http)?.let { "HTTP ${it.statusCode}: ${it.body?.take(400) ?: ""}" } ?: e.error.toString()
                else -> e.message ?: e::class.simpleName ?: "error"
            }
            false to detail
        }
        val (ok, detail) = result
        runCatching { record(channel.id, CAPABILITY_GEMINI_SIGNATURE_BYPASS, upstreamModel, ok, detail) }
            .onFailure { log.warn("capability verify record failed channel=${channel.id} model=$upstreamModel: ${it.message}") }
        if (!ok) log.warn("gemini signature bypass not verified channel=${channel.id} model=$upstreamModel: ${detail ?: ""}".take(600))
        return ok
    }

    private fun probeBody(): String = """
        {"contents":[
          {"role":"user","parts":[{"text":"Call ng_probe, then reply with OK."}]},
          {"role":"model","parts":[{"functionCall":{"name":"ng_probe","args":{}},"thoughtSignature":"${GeminiThoughtSignatures.BYPASS_VALUE}"}]},
          {"role":"user","parts":[{"functionResponse":{"name":"ng_probe","response":{"ok":true}}}]}
        ],
        "tools":[{"functionDeclarations":[{"name":"ng_probe","description":"Connectivity probe.","parameters":{"type":"object","properties":{}}}]}],
        "generationConfig":{"maxOutputTokens":16}}
    """.trimIndent()

    companion object {
        const val CAPABILITY_GEMINI_SIGNATURE_BYPASS = "gemini_signature_bypass"
        val GEMINI_TYPES = setOf("gemini", "vertex_gemini")
        /** 记录有效期：验证任务每 6 小时一次，留出一次失败重试的余量。 */
        const val VALIDITY_MS = 24L * 60 * 60 * 1000
        const val CACHE_MS = 60_000L

        private val cacheMutex = Mutex()
        private val cache = HashMap<String, Pair<Boolean, Long>>()
        private fun now() = kotlin.time.Clock.System.now().toEpochMilliseconds()
    }
}
