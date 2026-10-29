package logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OutputCapTest {

    private fun field(body: String, vararg path: String): Int? {
        var o = Json.parseToJsonElement(body).jsonObject
        for (p in path.dropLast(1)) o = o[p]?.jsonObject ?: return null
        return o[path.last()]?.jsonPrimitive?.int
    }

    @Test fun openAiChatGetsMaxTokens() {
        val out = OutputCap.inject("""{"model":"gpt-4o-mini","messages":[]}""", "openai", 4096)
        assertEquals(4096, field(out, "max_tokens"))
        assertEquals("gpt-4o-mini", Json.parseToJsonElement(out).jsonObject["model"]?.jsonPrimitive?.content)
    }

    @Test fun responsesGetsMaxOutputTokens() {
        assertEquals(2048, field(OutputCap.inject("""{"model":"m","input":"hi"}""", "responses", 2048), "max_output_tokens"))
    }

    @Test fun anthropicGetsMaxTokens() {
        assertEquals(1024, field(OutputCap.inject("""{"model":"m","messages":[]}""", "anthropic", 1024), "max_tokens"))
    }

    @Test fun geminiCapMergesIntoExistingGenerationConfig() {
        val out = OutputCap.inject("""{"contents":[],"generationConfig":{"temperature":0.2}}""", "gemini", 8192)
        assertEquals(8192, field(out, "generationConfig", "maxOutputTokens"))
        assertEquals("0.2", Json.parseToJsonElement(out).jsonObject["generationConfig"]!!.jsonObject["temperature"]!!.jsonPrimitive.content)
    }

    @Test fun clientCapIsNeverOverwritten() {
        val body = """{"model":"m","max_completion_tokens":50}"""
        assertEquals(body, OutputCap.inject(body, "openai", 4096))
    }

    @Test fun unknownProtocolOrBadJsonIsLeftAlone() {
        assertEquals("not json", OutputCap.inject("not json", "openai", 4096))
        assertNull(field(OutputCap.inject("""{"model":"m"}""", "bedrock", 4096), "max_tokens"))
    }
}
