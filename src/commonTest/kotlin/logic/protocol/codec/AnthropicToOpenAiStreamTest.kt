package logic.protocol.codec

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnthropicToOpenAiStreamTest {
    private fun dataLines(out: String): List<String> =
        out.split("\n\n").filter { it.isNotBlank() }
            .mapNotNull { b -> b.lineSequence().firstOrNull { it.startsWith("data:") }?.removePrefix("data:")?.trim() }

    @Test
    fun textStreamProducesOpenAiChunks() {
        val sm = AnthropicToOpenAiStream(model = "gpt-x")
        val out = StringBuilder()
        out.append(sm.feed("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}\n\n"))
        out.append(sm.feed("event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"))
        out.append(sm.feed("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"He\"}}\n\n"))
        out.append(sm.feed("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"llo\"}}\n\n"))
        out.append(sm.feed("event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\n"))
        out.append(sm.feed("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"))
        out.append(sm.finish())

        val body = out.toString()
        val lines = dataLines(body)
        assertTrue(body.contains("chat.completion.chunk"))
        assertTrue(body.contains("\"content\":\"He\""))
        assertTrue(body.contains("\"content\":\"llo\""))
        assertTrue(body.contains("\"finish_reason\":\"stop\""))
        assertTrue(body.contains("\"completion_tokens\":2"))
        assertEquals("[DONE]", lines.last())
    }

    @Test
    fun toolUseStreamProducesToolCallChunks() {
        val sm = AnthropicToOpenAiStream(model = "m")
        val out = StringBuilder()
        out.append(sm.feed("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1}}}\n\n"))
        out.append(sm.feed("event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"get\"}}\n\n"))
        out.append(sm.feed("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"x\\\":1}\"}}\n\n"))
        out.append(sm.feed("event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":3}}\n\n"))
        out.append(sm.finish())
        val body = out.toString()
        assertTrue(body.contains("tool_calls"), body)
        assertTrue(body.contains("\"name\":\"get\""))
        assertTrue(body.contains("\"finish_reason\":\"tool_calls\""))
    }
}
