package logic.protocol

import logic.AnthropicUsageExtractor
import logic.RequestRewriter
import logic.Rewritten
import logic.Usage
import model.Channel

/** Anthropic Messages 协议。上游：x-api-key + anthropic-version；流式 usage 在 message_delta。 */
object AnthropicAdapter : ProtocolAdapter {
    override val id = "anthropic"

    override fun upstreamHeaders(apiKey: String) = mapOf(
        "x-api-key" to apiKey,
        "anthropic-version" to "2023-06-01",
        "Content-Type" to "application/json",
    )

    override fun upstreamPath(inboundPath: String) = "/v1/messages"

    /** 复用 model/param_override 改写，但不注入 OpenAI 专属 stream_options。 */
    override fun rewrite(rawBody: String, channel: Channel): Rewritten =
        RequestRewriter.rewrite(rawBody, channel, injectStreamOptions = false)

    override fun extractUsage(responseBody: String): Usage? = AnthropicUsageExtractor.fromJson(responseBody)

    // Anthropic Messages 固定单响应，无 n；requestBody 不需要，终止判定用 message_stop。
    override fun streamUsageAccumulator(requestBody: String): StreamUsageAccumulator = AnthropicUsageExtractor.accumulator()

    override fun errorBody(code: String, message: String): String {
        val m = kotlinx.serialization.json.JsonPrimitive(message).toString()
        return """{"type":"error","error":{"type":"$code","message":$m}}"""
    }
}
