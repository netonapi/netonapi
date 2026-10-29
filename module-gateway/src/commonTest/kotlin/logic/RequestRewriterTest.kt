package logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestRewriterTest {
    private val channel = model.Channel(
        id = 1, name = "c", type = "openai_compatible", baseUrl = "http://u",
        models = "gpt-test", modelMapping = """{"gpt-test":"upstream-x"}""",
        paramOverride = """{"temperature":0.3}""",
    )

    @Test
    fun rewritesModelAndPreservesUnknownFields() {
        val body = """{"model":"gpt-test","messages":[],"reasoning_effort":"high","stream":false}"""
        val r = RequestRewriter.rewrite(body, channel)
        assertEquals("upstream-x", r.upstreamModel)
        assertFalse(r.stream)
        assertTrue(r.body.contains(""""reasoning_effort":"high""""), "未知字段必须保真: ${r.body}")
        assertTrue(r.body.contains(""""model":"upstream-x""""))
    }

    @Test
    fun mappingMissKeepsModel() {
        val r = RequestRewriter.rewrite("""{"model":"other","messages":[]}""",
            channel.copy(models = "other"))
        assertEquals("other", r.upstreamModel)
    }

    @Test
    fun paramOverrideWins() {
        val r = RequestRewriter.rewrite("""{"model":"gpt-test","temperature":0.9}""", channel)
        assertTrue(r.body.contains(""""temperature":0.3"""), r.body)
        assertFalse(r.body.contains("0.9"))
    }

    @Test
    fun streamInjectsIncludeUsage() {
        val r = RequestRewriter.rewrite("""{"model":"gpt-test","stream":true}""", channel)
        assertTrue(r.stream)
        assertTrue(r.body.contains(""""stream_options":{"include_usage":true}"""), r.body)
    }

    @Test
    fun streamOptionsNotOverwrittenIfPresent() {
        val body = """{"model":"gpt-test","stream":true,"stream_options":{"include_usage":false}}"""
        val r = RequestRewriter.rewrite(body, channel)
        assertTrue(r.body.contains(""""include_usage":false"""))
    }

    @Test
    fun nonStreamNoInjection() {
        val r = RequestRewriter.rewrite("""{"model":"gpt-test"}""", channel)
        assertFalse(r.body.contains("stream_options"))
    }
}
