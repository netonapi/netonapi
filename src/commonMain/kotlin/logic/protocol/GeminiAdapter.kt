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

    override fun streamUsageAccumulator(): StreamUsageAccumulator = object : StreamUsageAccumulator {
        private val buffer = StringBuilder()
        private var last: Usage? = null
        override fun feed(chunk: String) {
            buffer.append(chunk)
            while (true) {
                val idx = buffer.indexOf("\n\n"); if (idx < 0) break
                val ev = buffer.substring(0, idx); buffer.deleteRange(0, idx + 2)
                for (line in ev.lineSequence()) {
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == line) continue
                    val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    (obj["usageMetadata"] as? JsonObject)?.let {
                        last = Usage(
                            promptTokens = it["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                            completionTokens = it["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                        )
                    }
                }
            }
        }
        override fun usage() = last
    }

    override fun errorBody(code: String, message: String): String {
        val m = JsonPrimitive(message).toString()
        return """{"error":{"code":400,"status":"$code","message":$m}}"""
    }
}
