package logic

import kotlinx.serialization.json.*

/** 从上游响应提取 usage（OpenAI 兼容格式）。 */
object UsageExtractor {

    private val json = Json { ignoreUnknownKeys = true }

    fun fromJson(body: String): Usage? {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return fromUsageObject(obj["usage"] as? JsonObject ?: return null)
    }

    /**
     * 两种 OpenAI usage 形状：Chat Completions 的 prompt_/completion_tokens，
     * Responses API 的 input_/output_tokens（缓存命中在 input_tokens_details.cached_tokens）。
     */
    fun fromUsageObject(usage: JsonObject): Usage {
        fun int(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.intOrNull
        val details = (usage["prompt_tokens_details"] ?: usage["input_tokens_details"]) as? JsonObject
        // rerank / 部分 embeddings 只给 total_tokens：全算输入
        val totalOnly = if (usage["prompt_tokens"] == null && usage["input_tokens"] == null) int(usage, "total_tokens") else null
        var prompt = int(usage, "prompt_tokens") ?: int(usage, "input_tokens") ?: totalOnly ?: 0
        var cached = int(details, "cached_tokens") ?: 0
        // DeepSeek 原生口径：总输入 = 命中 + 未命中。中转站常把 prompt_tokens 报得和它对不上（实测 27 vs 未命中 86）——
        // 两者取大，宁可按上游自己报的较大值计，也不少收。
        val hit = int(usage, "prompt_cache_hit_tokens")
        val miss = int(usage, "prompt_cache_miss_tokens")
        if (hit != null || miss != null) {
            prompt = maxOf(prompt, (hit ?: 0) + (miss ?: 0))
            cached = maxOf(cached, hit ?: 0)
        }
        return Usage(
            promptTokens = prompt,
            completionTokens = int(usage, "completion_tokens") ?: int(usage, "output_tokens") ?: 0,
            cacheReadTokens = cached,
            cacheWriteTokens = 0,
            // OpenAI 口径 prompt 含缓存读；缓存读比 prompt 还多，说明这个上游报的 prompt 不含缓存（实测 80 vs 128），
            // 按「新输入 + 缓存读」分开计，否则会把新输入当成 0。
            promptIncludesCache = cached <= prompt,
        )
    }
}

/**
 * 流式直通时旁路累积 SSE 文本，记录最后一个含 usage 的 data 行（OpenAI include_usage 尾包）。
 * feed 输入与转发给客户端的块完全相同——只观察不修改。
 */
class SseUsageAccumulator(private val expectedChoices: Int = 1) {
    private val framer = logic.protocol.SseEventFramer()
    private var last: Usage? = null
    private var doneSeen = false
    private val finishedIdx = mutableSetOf<Int>()
    private val json = Json { ignoreUnknownKeys = true }

    fun feed(chunk: String) {
        for (raw in framer.frame(chunk)) {
            val data = raw.trim()
            if (data.isEmpty()) continue
            if (data == "[DONE]") { doneSeen = true; continue }
            val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
            // Responses API 的流：终态事件 response.completed / incomplete / failed 带完整 usage；error 事件也是终态
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            if (type != null && type.startsWith("response.")) {
                if (type == "response.completed" || type == "response.incomplete" || type == "response.failed") {
                    ((obj["response"] as? JsonObject)?.get("usage") as? JsonObject)?.let { last = UsageExtractor.fromUsageObject(it) }
                    doneSeen = true
                }
                continue
            }
            if (type == "error") { doneSeen = true; continue }
            (obj["usage"] as? JsonObject)?.let { last = UsageExtractor.fromUsageObject(it) }
            (obj["choices"] as? JsonArray)?.forEach { c ->
                val co = c as? JsonObject ?: return@forEach
                val idx = co["index"]?.jsonPrimitive?.intOrNull ?: 0
                val fr = co["finish_reason"]
                if (fr != null && fr !is JsonNull && fr.jsonPrimitive.contentOrNull?.isNotEmpty() == true) finishedIdx += idx
            }
        }
    }

    fun usage(): Usage? = last
    // [DONE] 是 OpenAI 绝对终止；无 [DONE] 时，请求期望的全部 n 个 choice index（0..n-1）都必须 finish——
    // 仅"已观察到的都 finish"无法发现请求 n=2 但 index 1 从未出现的截断。
    fun terminalSeen(): Boolean = doneSeen || (expectedChoices > 0 && (0 until expectedChoices).all { it in finishedIdx })
}
