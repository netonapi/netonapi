package logic.protocol.aws

import kotlinx.serialization.json.*
import logic.protocol.AnthropicAdapter
import logic.protocol.ByteStreamDecoder
import logic.protocol.ProtocolAdapter
import model.Channel

/**
 * AWS Bedrock 上的 Anthropic 模型：协议就是 Anthropic Messages（复用 [AnthropicAdapter] 的改写、usage、错误体），
 * 不同的只有：
 * - 鉴权 SigV4：渠道 Key 填 `ACCESS_KEY_ID:SECRET_ACCESS_KEY[:SESSION_TOKEN]`；region 取 Base URL 的主机名
 *   （bedrock-runtime.us-east-1.amazonaws.com），也可在厂商参数 JSON 里写 `{"region":"us-east-1"}`；
 * - 路径 `/model/{modelId}/invoke` 或 `/invoke-with-response-stream`，模型 id 就是模型映射的目标名
 *   （如 anthropic.claude-sonnet-4-20250514-v1:0）；
 * - body 去掉 model / stream、补 anthropic_version；
 * - 流式响应是 AWS event-stream 二进制帧，由 [EventStreamDecoder] 还原成 SSE。
 */
object BedrockAdapter : ProtocolAdapter by AnthropicAdapter {
    private const val SERVICE = "bedrock"

    override fun upstreamHeaders(apiKey: String) = mapOf("Content-Type" to "application/json")

    override fun upstreamPath(inboundPath: String, channel: Channel, upstreamModel: String, stream: Boolean): String {
        val id = SigV4.uriEncode(upstreamModel, encodeSlash = true)
        return "/model/$id/" + if (stream) "invoke-with-response-stream" else "invoke"
    }

    override fun upstreamBody(body: String, upstreamModel: String, stream: Boolean): String {
        val src = Json.parseToJsonElement(body).jsonObject
        return buildJsonObject {
            put("anthropic_version", "bedrock-2023-05-31")
            src.forEach { (k, v) -> if (k != "model" && k != "stream") put(k, v) }
        }.toString()
    }

    override suspend fun requestHeaders(apiKey: String, method: String, url: String, body: String, channel: Channel): Map<String, String> {
        val creds = SigV4.parseCredentials(apiKey) ?: throw IllegalArgumentException("Bedrock 渠道 Key 必须是 ACCESS_KEY_ID:SECRET_ACCESS_KEY[:SESSION_TOKEN]")
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?')
        val configured = channel.providerConfig?.takeIf { it.isNotBlank() }?.let { runCatching { Json.parseToJsonElement(it).jsonObject["region"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
        val region = configured ?: SigV4.regionFromHost(host)
            ?: throw IllegalArgumentException("无法从 $host 推出 AWS region，请用 bedrock-runtime.<region>.amazonaws.com 作为 Base URL 或在厂商参数里写 {\"region\":\"us-east-1\"}")
        val extra = mapOf("Content-Type" to "application/json", "Accept" to "application/json")
        return SigV4.sign(creds, region, SERVICE, method, url, extra, body.encodeToByteArray(), kotlin.time.Clock.System.now().toEpochMilliseconds()).headers
    }

    override fun byteStreamDecoder(): ByteStreamDecoder = EventStreamDecoder()
}
