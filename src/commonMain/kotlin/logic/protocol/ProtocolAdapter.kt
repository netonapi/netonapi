package logic.protocol

import logic.Rewritten
import logic.Usage
import model.Channel

/** 入站/上游协议适配：认证头、请求改写、usage 提取、流式旁路、错误体各协议不同。 */
interface ProtocolAdapter {
    val id: String

    /** 上游请求认证/版本头。 */
    fun upstreamHeaders(apiKey: String): Map<String, String>

    /** 上游端点相对路径（挂在 channel.baseUrl 后）。 */
    fun upstreamPath(inboundPath: String): String

    fun rewrite(rawBody: String, channel: Channel): Rewritten

    /** 非流式响应体 → usage。 */
    fun extractUsage(responseBody: String): Usage?

    /** 流式旁路累加器（喂入透传的每个文本块）。requestBody 用于按协议解析期望 candidate 数（OpenAI n / Gemini candidateCount）。 */
    fun streamUsageAccumulator(requestBody: String): StreamUsageAccumulator

    /** 协议原生 error JSON。 */
    fun errorBody(code: String, message: String): String

    // ── 下面三个钩子给「协议相同、但鉴权/路径/流帧不同」的托管上游用（Bedrock / Vertex / Azure）──

    /** 上游路径；null = 由 RelayEngine 按协议默认规则决定。 */
    fun upstreamPath(inboundPath: String, channel: Channel, upstreamModel: String, stream: Boolean): String? = null

    /** 发给上游的 body（Bedrock 要去掉 model/stream、加 anthropic_version 等）。 */
    fun upstreamBody(body: String, upstreamModel: String, stream: Boolean): String = body

    /** 每请求的鉴权头（SigV4 要签 url + body，Vertex 要换 OAuth token）。默认退回静态头。 */
    suspend fun requestHeaders(apiKey: String, method: String, url: String, body: String, channel: Channel): Map<String, String> = upstreamHeaders(apiKey)

    /** 非 SSE 的流帧解码（Bedrock 的 AWS event-stream）：把上游字节转成 SSE 文本；null = 上游本来就是 SSE。 */
    fun byteStreamDecoder(): ByteStreamDecoder? = null
}

/** 字节流 → SSE 文本的增量解码器；finish 返回残留（正常为空）。 */
interface ByteStreamDecoder {
    fun feed(bytes: ByteArray): String
    fun finish(): String
}

/** 流式 usage 旁路累加器（协议无关接口）。 */
interface StreamUsageAccumulator {
    fun feed(chunk: String)
    fun usage(): Usage?

    /**
     * 是否已见到协议级终止标记（OpenAI `[DONE]`/非空 finish_reason；Anthropic `message_stop`；Gemini 终态 finishReason）。
     * 传输层 EOF 但未见终止标记 = 上游截断 → 应按 PARTIAL 收尾（不得伪造正常结束）。
     */
    fun terminalSeen(): Boolean
}
