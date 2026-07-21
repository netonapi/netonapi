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

    /** 流式旁路累加器（喂入透传的每个文本块）。 */
    fun streamUsageAccumulator(): StreamUsageAccumulator

    /** 协议原生 error JSON。 */
    fun errorBody(code: String, message: String): String
}

/** 流式 usage 旁路累加器（协议无关接口）。 */
interface StreamUsageAccumulator {
    fun feed(chunk: String)
    fun usage(): Usage?
}
