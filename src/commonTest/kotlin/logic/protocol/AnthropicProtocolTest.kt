package logic.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnthropicProtocolTest {
    @Test
    fun anthropicHeadersUseApiKeyNotBearer() {
        val h = AnthropicAdapter.upstreamHeaders("sk-ant-xxx")
        assertEquals("sk-ant-xxx", h["x-api-key"])
        assertEquals("2023-06-01", h["anthropic-version"])
        assertFalse(h.containsKey("Authorization"))
    }

    @Test
    fun anthropicRewriteDoesNotInjectStreamOptions() {
        val ch = model.Channel(id = 1, name = "c", type = "anthropic", baseUrl = "http://u", models = "claude-test")
        val r = AnthropicAdapter.rewrite("""{"model":"claude-test","stream":true,"max_tokens":100}""", ch)
        assertTrue(r.stream)
        assertFalse(r.body.contains("stream_options"))
    }

    @Test
    fun anthropicNonStreamUsage() {
        val u = AnthropicAdapter.extractUsage("""{"type":"message","usage":{"input_tokens":1000,"output_tokens":500}}""")!!
        assertEquals(1000, u.promptTokens)
        assertEquals(500, u.completionTokens)
    }

    @Test
    fun anthropicStreamUsageFromStartAndDelta() {
        val acc = AnthropicAdapter.streamUsageAccumulator()
        acc.feed("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1000,\"output_tokens\":1}}}\n\n")
        acc.feed("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"hi\"}}\n\n")
        acc.feed("event: message_delta\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":500}}\n\n")
        val u = acc.usage()!!
        assertEquals(1000, u.promptTokens)
        assertEquals(500, u.completionTokens)
    }

    @Test
    fun anthropicErrorFormat() {
        val body = AnthropicAdapter.errorBody("overloaded_error", "upstream busy")
        assertTrue(body.contains("\"type\":\"error\""))
        assertTrue(body.contains("\"type\":\"overloaded_error\""))
    }

    @Test
    fun openaiAdapterUnchangedBehavior() {
        val h = OpenAiAdapter.upstreamHeaders("sk-x")
        assertEquals("Bearer sk-x", h["Authorization"])
        val ch = model.Channel(id = 1, name = "c", type = "openai_compatible", baseUrl = "http://u", models = "gpt-test")
        val r = OpenAiAdapter.rewrite("""{"model":"gpt-test","stream":true}""", ch)
        assertTrue(r.body.contains("stream_options"))
    }
}
