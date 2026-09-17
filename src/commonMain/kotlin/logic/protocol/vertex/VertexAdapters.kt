package logic.protocol.vertex

import kotlinx.serialization.json.*
import logic.protocol.AnthropicAdapter
import logic.protocol.GeminiAdapter
import logic.protocol.ProtocolAdapter
import model.Channel

/**
 * Vertex AI 上的 Anthropic 模型：Messages 协议不变；路径
 * `/v1/projects/{p}/locations/{l}/publishers/anthropic/models/{model}:rawPredict`（流式 `:streamRawPredict`），
 * body 去掉 model、补 anthropic_version=vertex-2023-10-16；鉴权 OAuth Bearer（见 [VertexAuth]）。
 */
object VertexAnthropicAdapter : ProtocolAdapter by AnthropicAdapter {
    override fun upstreamHeaders(apiKey: String) = mapOf("Content-Type" to "application/json")

    override fun upstreamPath(inboundPath: String, channel: Channel, upstreamModel: String, stream: Boolean): String {
        val (p, l) = VertexAuth.projectAndLocation(channel)
        return "/v1/projects/$p/locations/$l/publishers/anthropic/models/$upstreamModel:" + if (stream) "streamRawPredict" else "rawPredict"
    }

    override fun upstreamBody(body: String, upstreamModel: String, stream: Boolean): String {
        val src = Json.parseToJsonElement(body).jsonObject
        return buildJsonObject {
            put("anthropic_version", "vertex-2023-10-16")
            src.forEach { (k, v) -> if (k != "model") put(k, v) }
        }.toString()
    }

    override suspend fun requestHeaders(apiKey: String, method: String, url: String, body: String, channel: Channel): Map<String, String> =
        mapOf("Authorization" to "Bearer ${VertexAuth.bearer(apiKey)}", "Content-Type" to "application/json")
}

/** Vertex AI 上的 Gemini：body 与 Gemini API 相同，只是路径带 project/location、鉴权是 OAuth Bearer。 */
object VertexGeminiAdapter : ProtocolAdapter by GeminiAdapter {
    override fun upstreamHeaders(apiKey: String) = mapOf("Content-Type" to "application/json")

    override fun upstreamPath(inboundPath: String, channel: Channel, upstreamModel: String, stream: Boolean): String {
        val (p, l) = VertexAuth.projectAndLocation(channel)
        return "/v1/projects/$p/locations/$l/publishers/google/models/$upstreamModel:" + if (stream) "streamGenerateContent?alt=sse" else "generateContent"
    }

    override suspend fun requestHeaders(apiKey: String, method: String, url: String, body: String, channel: Channel): Map<String, String> =
        mapOf("Authorization" to "Bearer ${VertexAuth.bearer(apiKey)}", "Content-Type" to "application/json")
}
