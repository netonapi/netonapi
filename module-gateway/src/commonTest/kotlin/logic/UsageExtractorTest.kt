package logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsageExtractorTest {
    @Test
    fun extractsFromNonStreamResponse() {
        val body = """{"id":"x","usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120}}"""
        val u = UsageExtractor.fromJson(body)!!
        assertEquals(100, u.promptTokens)
        assertEquals(20, u.completionTokens)
    }

    @Test
    fun extractsCachedTokens() {
        val body = """{"usage":{"prompt_tokens":100,"completion_tokens":1,"prompt_tokens_details":{"cached_tokens":60}}}"""
        assertEquals(60, UsageExtractor.fromJson(body)!!.cacheReadTokens)
    }

    @Test
    fun nullWhenNoUsage() {
        assertNull(UsageExtractor.fromJson("""{"id":"x"}"""))
        assertNull(UsageExtractor.fromJson("not json"))
    }

    @Test
    fun sseAccumulatorFindsTrailingUsage() {
        val acc = SseUsageAccumulator()
        acc.feed("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n")
        acc.feed("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}\n\n")
        acc.feed("data: [DONE]\n\n")
        val u = acc.usage()!!
        assertEquals(7, u.promptTokens)
        assertEquals(3, u.completionTokens)
    }

    @Test
    fun sseAccumulatorHandlesTornChunks() {
        val acc = SseUsageAccumulator()
        val line = "data: {\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":5}}\n\n"
        // 在 JSON 中间撕裂
        acc.feed(line.substring(0, 20))
        acc.feed(line.substring(20))
        assertEquals(11, acc.usage()!!.promptTokens)
    }

    @Test
    fun sseAccumulatorNullWithoutUsage() {
        val acc = SseUsageAccumulator()
        acc.feed("data: {\"choices\":[]}\n\ndata: [DONE]\n\n")
        assertNull(acc.usage())
    }
}
