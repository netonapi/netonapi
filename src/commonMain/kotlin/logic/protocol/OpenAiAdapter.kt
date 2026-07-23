package logic.protocol

import logic.RequestRewriter
import logic.Rewritten
import logic.SseUsageAccumulator
import logic.Usage
import logic.UsageExtractor
import model.Channel

/** OpenAI 兼容协议（chat/completions、embeddings）。 */
object OpenAiAdapter : ProtocolAdapter {
    override val id = "openai"

    override fun upstreamHeaders(apiKey: String) = mapOf(
        "Authorization" to "Bearer $apiKey",
        "Content-Type" to "application/json",
    )

    override fun upstreamPath(inboundPath: String) = inboundPath

    override fun rewrite(rawBody: String, channel: Channel): Rewritten =
        RequestRewriter.rewrite(rawBody, channel)

    override fun extractUsage(responseBody: String): Usage? = UsageExtractor.fromJson(responseBody)

    override fun streamUsageAccumulator(): StreamUsageAccumulator = object : StreamUsageAccumulator {
        private val acc = SseUsageAccumulator()
        override fun feed(chunk: String) = acc.feed(chunk)
        override fun usage() = acc.usage()
        override fun terminalSeen() = acc.terminalSeen()
    }

    override fun errorBody(code: String, message: String): String {
        val m = kotlinx.serialization.json.JsonPrimitive(message).toString()
        return """{"error":{"message":$m,"type":"$code","code":"$code"}}"""
    }
}
