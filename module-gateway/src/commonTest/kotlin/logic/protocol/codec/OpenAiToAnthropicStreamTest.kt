package logic.protocol.codec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiToAnthropicStreamTest {
    private fun eventsOf(out: String): List<String> =
        out.split("\n\n").filter { it.isNotBlank() }
            .mapNotNull { block -> block.lineSequence().firstOrNull { it.startsWith("event:") }?.removePrefix("event:")?.trim() }

    @Test
    fun textStreamProducesValidAnthropicSequence() {
        val sm = OpenAiToAnthropicStream(model = "claude-x")
        val out = StringBuilder()
        out.append(sm.feed("""data: {"choices":[{"delta":{"role":"assistant","content":"He"}}]}""" + "\n\n"))
        out.append(sm.feed("""data: {"choices":[{"delta":{"content":"llo"}}]}""" + "\n\n"))
        out.append(sm.feed("""data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2}}""" + "\n\n"))
        out.append(sm.feed("data: [DONE]\n\n"))
        out.append(sm.finish())

        val events = eventsOf(out.toString())
        assertEquals(
            listOf("message_start", "content_block_start", "content_block_delta", "content_block_delta",
                   "content_block_stop", "message_delta", "message_stop"),
            events
        )
        assertTrue(out.contains("\"text\":\"He\""))
        assertTrue(out.contains("\"text\":\"llo\""))
        assertTrue(out.contains("\"stop_reason\":\"end_turn\""))
        assertTrue(out.contains("\"output_tokens\":2"))
        assertTrue(out.contains("\"input_tokens\":10"))
    }

    @Test
    fun emptyStreamStillWellFormed() {
        val sm = OpenAiToAnthropicStream(model = "m")
        val out = StringBuilder()
        out.append(sm.feed("""data: {"choices":[{"delta":{"content":"hi"}}]}""" + "\n\n"))
        out.append(sm.finish())
        val events = eventsOf(out.toString())
        // 即使上游没发 finish_reason，finish() 也要补齐收尾事件
        assertTrue(events.first() == "message_start")
        assertTrue(events.last() == "message_stop")
        assertTrue(events.contains("content_block_stop"))
    }

    @Test
    fun toolCallStreamProducesToolUseBlock() {
        val sm = OpenAiToAnthropicStream(model = "m")
        val out = StringBuilder()
        out.append(sm.feed("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"get","arguments":""}}]}}]}""" + "\n\n"))
        out.append(sm.feed("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"x\":1}"}}]}}]}""" + "\n\n"))
        out.append(sm.feed("""data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""" + "\n\n"))
        out.append(sm.finish())
        assertTrue(out.contains("tool_use"), out.toString())
        assertTrue(out.contains("input_json_delta"))
        assertTrue(out.contains("\"stop_reason\":\"tool_use\""))
    }
}
