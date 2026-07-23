package logic.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/** SSE 分帧 / 增量 UTF-8 解码 / 多 candidate 终止标记的回归。 */
class SseStreamingTest {

    // ===== SseEventFramer =====

    @Test fun framer_handles_lf_crlf_cr() {
        assertEquals(listOf("a"), SseEventFramer().frame("data: a\n\n"))
        assertEquals(listOf("b"), SseEventFramer().frame("data: b\r\n\r\n"))
        assertEquals(listOf("c"), SseEventFramer().frame("data: c\r\r"))
    }

    @Test fun framer_boundary_split_across_chunks() {
        val f = SseEventFramer()
        assertEquals(emptyList<String>(), f.frame("data: hello\r\n"))
        assertEquals(listOf("hello"), f.frame("\r\n"))
    }

    @Test fun framer_crlf_split_across_chunks() {
        val f = SseEventFramer()
        assertEquals(emptyList<String>(), f.frame("data: x\r")) // \r 落在本 chunk 末尾
        assertEquals(listOf("x"), f.frame("\n\r\n"))            // 下一 chunk 的 \n 属于上一个 CRLF
    }

    @Test fun framer_multiline_data() {
        assertEquals(listOf("line1\nline2"), SseEventFramer().frame("data: line1\ndata: line2\n\n"))
    }

    @Test fun framer_data_split_mid_line() {
        val f = SseEventFramer()
        assertEquals(emptyList<String>(), f.frame("data: {\"a\":"))
        assertEquals(listOf("{\"a\":1}"), f.frame("1}\n\n"))
    }

    // ===== Utf8StreamDecoder =====

    @Test fun utf8_reassembles_byte_at_a_time() {
        val d = Utf8StreamDecoder()
        val bytes = "你好😀世界".encodeToByteArray()
        val sb = StringBuilder()
        for (b in bytes) sb.append(d.decode(byteArrayOf(b)))
        sb.append(d.flush())
        assertEquals("你好😀世界", sb.toString())
    }

    @Test fun utf8_no_replacement_on_arbitrary_split() {
        val full = """{"t":"世界🌍 hello"}"""
        val bytes = full.encodeToByteArray()
        for (cut in 1 until bytes.size) {
            val d = Utf8StreamDecoder()
            val a = d.decode(bytes.copyOfRange(0, cut))
            val b = d.decode(bytes.copyOfRange(cut, bytes.size))
            val out = a + b + d.flush()
            assertEquals(full, out, "cut=$cut")
            assertFalse((a + b).contains('�'), "replacement char at cut=$cut")
        }
    }

    // ===== 多 candidate 终止 =====

    @Test fun openai_terminal_requires_all_observed_choices_finished() {
        val acc = OpenAiAdapter.streamUsageAccumulator()
        acc.feed("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"}},{\"index\":1,\"delta\":{\"content\":\"b\"}}]}\n\n")
        assertFalse(acc.terminalSeen())
        acc.feed("data: {\"choices\":[{\"index\":0,\"finish_reason\":\"stop\"}]}\n\n")
        assertFalse(acc.terminalSeen(), "index 1 未 finish 不应终止")
        acc.feed("data: {\"choices\":[{\"index\":1,\"finish_reason\":\"stop\"}]}\n\n")
        assertTrue(acc.terminalSeen(), "两个 candidate 都 finish → 终止")
    }

    @Test fun openai_done_is_absolute_terminal() {
        val acc = OpenAiAdapter.streamUsageAccumulator()
        acc.feed("data: {\"choices\":[{\"index\":0,\"delta\":{}}]}\n\ndata: [DONE]\n\n")
        assertTrue(acc.terminalSeen())
    }

    @Test fun openai_no_terminal_when_truncated() {
        val acc = OpenAiAdapter.streamUsageAccumulator()
        acc.feed("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"}}]}\n\n")
        assertFalse(acc.terminalSeen(), "无 finish_reason / [DONE] 的中断流不应判终止")
    }

    @Test fun openai_terminal_survives_crlf() {
        val acc = OpenAiAdapter.streamUsageAccumulator()
        acc.feed("data: {\"choices\":[{\"index\":0,\"finish_reason\":\"stop\"}]}\r\n\r\n")
        assertTrue(acc.terminalSeen(), "CRLF 分帧下 finish_reason 仍应被解析")
    }
}
