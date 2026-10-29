package logic.protocol.aws

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logic.RequestRewriter
import logic.Rewritten
import logic.Usage
import logic.protocol.ByteStreamDecoder
import logic.protocol.ProtocolAdapter
import logic.protocol.StreamUsageAccumulator
import model.Channel
import neton.ai.protocol.convert.ProtocolConverter
import neton.ai.protocol.ir.WireFormat
import neton.ai.protocol.transport.BedrockUpstream

/**
 * Amazon Bedrock Converse：Bedrock 上任意模型（Nova、Llama、Mistral、Claude 等）的统一接口。
 * 协议转换在 neton-ai（`WireFormat.BEDROCK_CONVERSE`）；鉴权与 region 规则同 [BedrockAdapter]。
 * 客户端不会直接说 Converse，所以它只作上游：入站请求经 hub 转成 Converse。
 */
object BedrockConverseAdapter : ProtocolAdapter {
    override val id = "bedrock_converse"
    private val json = Json { ignoreUnknownKeys = true }

    override fun upstreamHeaders(apiKey: String) = mapOf("Content-Type" to "application/json")

    override fun upstreamPath(inboundPath: String) = "/converse"

    override fun upstreamPath(inboundPath: String, channel: Channel, upstreamModel: String, stream: Boolean): String =
        BedrockUpstream.conversePath(upstreamModel, stream)

    override fun upstreamBody(body: String, upstreamModel: String, stream: Boolean): String =
        BedrockUpstream.converseBody(json.parseToJsonElement(body).jsonObject).toString()

    override fun rewrite(rawBody: String, channel: Channel): Rewritten =
        RequestRewriter.rewrite(rawBody, channel, injectStreamOptions = false)

    /** Converse 的 inputTokens 不含缓存读写，与 gateway 的 Anthropic 口径一致。 */
    override fun extractUsage(responseBody: String): Usage? {
        val u = runCatching { json.parseToJsonElement(responseBody).jsonObject["usage"] as? JsonObject }.getOrNull() ?: return null
        fun n(k: String) = (u[k] as? JsonPrimitive)?.intOrNull ?: 0
        return Usage(n("inputTokens"), n("outputTokens"), n("cacheReadInputTokens"), n("cacheWriteInputTokens"))
    }

    /** 喂入 event-stream 解出的 SSE 文本；用量与终止状态由 neton-ai 的流解码器判定。 */
    override fun streamUsageAccumulator(requestBody: String): StreamUsageAccumulator = object : StreamUsageAccumulator {
        private val meter = ProtocolConverter.streamConverter(WireFormat.BEDROCK_CONVERSE, WireFormat.BEDROCK_CONVERSE)
        override fun feed(chunk: String) { meter.feed(chunk) }
        override fun terminalSeen(): Boolean = meter.completed
        override fun usage(): Usage? {
            val u = meter.usage
            if (!u.hasAnyCount) return null
            val read = u.cacheReadTokens ?: 0
            val write = u.cacheWriteTokens ?: 0
            return Usage(((u.inputTokens ?: 0) - read - write).toInt().coerceAtLeast(0), (u.outputTokens ?: 0).toInt(), read.toInt(), write.toInt())
        }
    }

    override fun errorBody(code: String, message: String): String = """{"message":${JsonPrimitive(message)}}"""

    override suspend fun requestHeaders(apiKey: String, method: String, url: String, body: String, channel: Channel): Map<String, String> =
        BedrockAdapter.requestHeaders(apiKey, method, url, body, channel)

    override fun byteStreamDecoder(): ByteStreamDecoder = BedrockUpstream.streamDecoder()
}
