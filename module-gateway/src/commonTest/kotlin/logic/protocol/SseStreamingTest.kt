package logic.protocol

import logic.protocol.codec.AnthropicToOpenAiStream
import logic.protocol.codec.OpenAiToAnthropicStream
import logic.protocol.codec.GeminiToOpenAiStream
import logic.protocol.codec.OpenAiToGeminiStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFails

/** SSE 分帧 / 严格增量 UTF-8 / 期望 candidate 终止 / 各协议与 codec 接线的回归。 */
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
        assertEquals(emptyList<String>(), f.frame("data: x\r"))
        assertEquals(listOf("x"), f.frame("\n\r\n"))
    }

    @Test fun framer_empty_chunk_preserves_pending_cr() {
        val f = SseEventFramer()
        assertEquals(emptyList<String>(), f.frame("data: x\r")) // 末尾 \r 挂起
        assertEquals(emptyList<String>(), f.frame(""))          // 空 chunk 不得清 pendingCr
        assertEquals(emptyList<String>(), f.frame("\n"))        // 属于上一个 CRLF 的行结束，不是空行
        assertEquals(listOf("x"), f.frame("\r\n"))              // 真正的空行才产生事件
    }

    @Test fun framer_multiline_data() {
        assertEquals(listOf("line1\nline2"), SseEventFramer().frame("data: line1\ndata: line2\n\n"))
    }

    // ===== Utf8StreamDecoder（严格） =====

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

    @Test fun utf8_flush_throws_on_truncated_2byte() {
        val d = Utf8StreamDecoder()
        val two = "é".encodeToByteArray()           // 2-byte
        assertEquals("", d.decode(byteArrayOf(two[0]))) // 只喂 lead
        assertFails { d.flush() }                     // EOF 截断 → 抛，不吐 U+FFFD
    }

    @Test fun utf8_flush_throws_on_truncated_3byte() {
        val d = Utf8StreamDecoder()
        val three = "你".encodeToByteArray()          // E4 BD A0
        assertEquals("", d.decode(byteArrayOf(three[0], three[1])))
        assertFails { d.flush() }
    }

    @Test fun utf8_decode_throws_on_invalid_lead() {
        assertFails { Utf8StreamDecoder().decode(byteArrayOf(0xFF.toByte(), 0x41)) }
    }

    @Test fun utf8_flush_throws_on_lone_continuation() {
        val d = Utf8StreamDecoder()
        assertEquals("", d.decode(byteArrayOf(0x80.toByte()))) // 孤立 continuation：缓冲
        assertFails { d.flush() }
    }

    // ===== 期望 candidate 终止（OpenAI n） =====

    @Test fun openai_terminal_needs_all_expected_choices() {
        val acc = OpenAiAdapter.streamUsageAccumulator("""{"n":2}""")
        acc.feed("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"}},{\"index\":1,\"delta\":{\"content\":\"b\"}}]}\n\n")
        assertFalse(acc.terminalSeen())
        acc.feed("data: {\"choices\":[{\"index\":0,\"finish_reason\":\"stop\"}]}\n\n")
        assertFalse(acc.terminalSeen(), "index 1 未 finish 不应终止")
        acc.feed("data: {\"choices\":[{\"index\":1,\"finish_reason\":\"stop\"}]}\n\n")
        assertTrue(acc.terminalSeen())
    }

    @Test fun openai_missing_expected_candidate_is_not_terminal() {
        // 请求 n=2，损坏上游只发 index 0 并 finish，index 1 从未出现 → 必须判非终止
        val acc = OpenAiAdapter.streamUsageAccumulator("""{"n":2}""")
        acc.feed("data: {\"choices\":[{\"index\":0,\"finish_reason\":\"stop\"}]}\n\n")
        assertFalse(acc.terminalSeen(), "n=2 但仅 index 0 出现，不应判完整")
    }

    @Test fun openai_done_is_absolute_terminal() {
        val acc = OpenAiAdapter.streamUsageAccumulator("""{"n":1}""")
        acc.feed("data: {\"choices\":[{\"index\":0,\"delta\":{}}]}\n\ndata: [DONE]\n\n")
        assertTrue(acc.terminalSeen())
    }

    @Test fun openai_terminal_survives_crlf() {
        val acc = OpenAiAdapter.streamUsageAccumulator("""{"n":1}""")
        acc.feed("data: {\"choices\":[{\"index\":0,\"finish_reason\":\"stop\"}]}\r\n\r\n")
        assertTrue(acc.terminalSeen())
    }

    // ===== Anthropic message_stop =====

    @Test fun anthropic_message_stop_lf_and_crlf() {
        val lf = AnthropicAdapter.streamUsageAccumulator("")
        lf.feed("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
        assertTrue(lf.terminalSeen())
        val crlf = AnthropicAdapter.streamUsageAccumulator("")
        crlf.feed("event: message_stop\r\ndata: {\"type\":\"message_stop\"}\r\n\r\n")
        assertTrue(crlf.terminalSeen())
    }

    @Test fun anthropic_message_stop_char_fragmented() {
        val acc = AnthropicAdapter.streamUsageAccumulator("")
        val full = "data: {\"type\":\"message_stop\"}\n\n"
        for (ch in full) acc.feed(ch.toString())
        assertTrue(acc.terminalSeen())
    }

    @Test fun anthropic_missing_stop_is_not_terminal() {
        val acc = AnthropicAdapter.streamUsageAccumulator("")
        acc.feed("data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"hi\"}}\n\n")
        assertFalse(acc.terminalSeen())
    }

    // ===== Gemini candidateCount =====

    @Test fun gemini_terminal_needs_all_candidates() {
        val acc = GeminiAdapter.streamUsageAccumulator("""{"generationConfig":{"candidateCount":2}}""")
        acc.feed("data: {\"candidates\":[{\"index\":0,\"finishReason\":\"STOP\"}]}\n\n")
        assertFalse(acc.terminalSeen(), "candidate 1 未出现不应终止")
        acc.feed("data: {\"candidates\":[{\"index\":1,\"finishReason\":\"STOP\"}]}\n\n")
        assertTrue(acc.terminalSeen())
    }

    // ===== 四个 codec CRLF 接线 smoke =====

    @Test fun codecs_pass_text_through_crlf() {
        val a2o = AnthropicToOpenAiStream("m").feed(
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}\r\n\r\n")
        assertTrue(a2o.contains("hi"), "AnthropicToOpenAiStream CRLF: $a2o")

        val o2a = OpenAiToAnthropicStream("m").feed(
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\r\n\r\n")
        assertTrue(o2a.contains("hi"), "OpenAiToAnthropicStream CRLF: $o2a")

        val g2o = GeminiToOpenAiStream("m").feed(
            "data: {\"candidates\":[{\"index\":0,\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}\r\n\r\n")
        assertTrue(g2o.contains("hi"), "GeminiToOpenAiStream CRLF: $g2o")

        val o2g = OpenAiToGeminiStream("m").feed(
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\r\n\r\n")
        assertTrue(o2g.contains("hi"), "OpenAiToGeminiStream CRLF: $o2g")
    }
}
