package logic.protocol

/**
 * 增量 SSE 事件分帧（协议无关，供所有 usage 累加器与流 codec 复用）。
 *
 * 处理：
 *   - 事件边界 `\n\n` / `\r\n\r\n` / `\r\r`（统一归一为 `\n`）；
 *   - 边界或行被拆在任意 chunk 中（跨 chunk 的 CRLF、半个事件都能续接）；
 *   - 多行 `data:` 字段（按 SSE 规范以 `\n` 连接，去掉 `data:` 前缀与一个前导空格）。
 *
 * [frame] 返回本批已完整事件的 data 负载列表；无 data 字段的事件（注释/其它字段）被跳过。
 * 调用方自行判断 `"[DONE]"` 等终止标记。
 */
class SseEventFramer {
    private val buf = StringBuilder()
    private var pendingCr = false

    fun frame(chunk: String): List<String> {
        appendNormalized(chunk)
        val out = mutableListOf<String>()
        while (true) {
            val idx = buf.indexOf("\n\n")
            if (idx < 0) break
            val event = buf.substring(0, idx)
            buf.deleteRange(0, idx + 2)
            dataPayload(event)?.let { out += it }
        }
        return out
    }

    /** 归一行尾（\r\n / \r → \n），并处理跨 chunk 被拆开的 CRLF（上一 chunk 末尾的 \r）。 */
    private fun appendNormalized(chunk: String) {
        var i = 0
        if (pendingCr) {
            pendingCr = false
            if (chunk.isNotEmpty() && chunk[0] == '\n') i = 1  // 上一 chunk 的 \r 与本 chunk 的 \n 组成 CRLF
        }
        while (i < chunk.length) {
            val c = chunk[i]
            if (c == '\r') {
                buf.append('\n')
                when {
                    i + 1 >= chunk.length -> { pendingCr = true; i += 1 }
                    chunk[i + 1] == '\n' -> i += 2  // CRLF
                    else -> i += 1                  // 裸 CR
                }
            } else {
                buf.append(c); i += 1
            }
        }
    }

    /** 抽取事件内所有 `data:` 行，去前缀与一个前导空格，多行以 \n 连接；无 data 字段返回 null。 */
    private fun dataPayload(event: String): String? {
        val sb = StringBuilder()
        var has = false
        for (line in event.split('\n')) {
            if (line.startsWith("data:")) {
                has = true
                var d = line.substring(5)
                if (d.startsWith(" ")) d = d.substring(1)
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(d)
            }
        }
        return if (has) sb.toString() else null
    }
}
