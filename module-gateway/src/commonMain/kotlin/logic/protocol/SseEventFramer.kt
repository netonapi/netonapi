package logic.protocol

/**
 * 增量 SSE 分帧（实现在 neton-ai：[neton.ai.protocol.transport.SseFramer]），只取 data 负载，
 * 供 usage 累加器使用。调用方自行判断 `"[DONE]"` 等终止标记。
 */
class SseEventFramer {
    private val framer = neton.ai.protocol.transport.SseFramer()

    fun frame(chunk: String): List<String> = framer.feed(chunk).map { it.data }
}
