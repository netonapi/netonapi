package logic.protocol

import logic.RequestRewriter
import logic.Rewritten
import logic.Usage
import model.Channel
import kotlinx.serialization.json.*

/**
 * Gemini 协议。上游端点 /v1beta/models/{model}:{action}；认证 x-goog-api-key；
 * usage 字段 usageMetadata。model 在入站 URL 路径，由 controller 注入 body 后交引擎。
 */
object GeminiAdapter : ProtocolAdapter {
    override val id = "gemini"
    private val json = Json { ignoreUnknownKeys = true }

    override fun upstreamHeaders(apiKey: String) = mapOf(
        "x-goog-api-key" to apiKey,
        "Content-Type" to "application/json",
    )

    /** 入站 path 形如 /v1beta/models/{model}:{action}；上游用映射后的模型重建（在 RelayEngine 里已知 upstreamModel，
     *  但 adapter 无 channel 上下文——沿用入站 action，模型段由 RelayEngine 覆盖，见 upstreamPathFor）。 */
    override fun upstreamPath(inboundPath: String) = inboundPath

    override fun rewrite(rawBody: String, channel: Channel): Rewritten =
        RequestRewriter.rewrite(rawBody, channel, injectStreamOptions = false)

    override fun extractUsage(responseBody: String): Usage? {
        val obj = runCatching { json.parseToJsonElement(responseBody).jsonObject }.getOrNull() ?: return null
        val u = obj["usageMetadata"] as? JsonObject ?: return null
        return Usage(
            promptTokens = u["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = u["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    override fun streamUsageAccumulator(requestBody: String): StreamUsageAccumulator = object : StreamUsageAccumulator {
        private val framer = SseEventFramer()
        private var last: Usage? = null
        private val expected = candidateCount(requestBody)
        private val finishedIdx = mutableSetOf<Int>()
        override fun feed(chunk: String) {
            for (raw in framer.frame(chunk)) {
                val data = raw.trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                (obj["usageMetadata"] as? JsonObject)?.let {
                    last = Usage(
                        promptTokens = it["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                        completionTokens = it["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                    )
                }
                // 终态 finishReason（STOP/MAX_TOKENS/SAFETY 等非空）= 该 candidate 终止；需期望的全部 candidate 都终止
                (obj["candidates"] as? JsonArray)?.forEach { c ->
                    val co = c as? JsonObject ?: return@forEach
                    val idx = co["index"]?.jsonPrimitive?.intOrNull ?: 0
                    if (!co["finishReason"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty()) finishedIdx += idx
                }
            }
        }
        override fun usage() = last
        override fun terminalSeen() = expected > 0 && (0 until expected).all { it in finishedIdx }
    }

    private fun candidateCount(body: String): Int =
        runCatching {
            (json.parseToJsonElement(body).jsonObject["generationConfig"] as? JsonObject)
                ?.get("candidateCount")?.jsonPrimitive?.intOrNull
        }.getOrNull()?.coerceAtLeast(1) ?: 1

    override fun errorBody(code: String, message: String): String {
        val m = JsonPrimitive(message).toString()
        return """{"error":{"code":400,"status":"$code","message":$m}}"""
    }
}
