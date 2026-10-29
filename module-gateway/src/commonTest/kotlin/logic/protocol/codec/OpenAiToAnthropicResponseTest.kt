package logic.protocol.codec

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenAiToAnthropicResponseTest {
    private val json = Json { }
    private fun conv(body: String) = json.parseToJsonElement(OpenAiToAnthropicResponse.convert(body)).jsonObject

    @Test
    fun plainTextResponse() {
        val out = conv("""{"id":"r1","model":"gpt","choices":[{"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}""")
        assertEquals("message", out["type"]!!.jsonPrimitive.content)
        assertEquals("assistant", out["role"]!!.jsonPrimitive.content)
        val block = out["content"]!!.jsonArray[0].jsonObject
        assertEquals("text", block["type"]!!.jsonPrimitive.content)
        assertEquals("hello", block["text"]!!.jsonPrimitive.content)
        assertEquals("end_turn", out["stop_reason"]!!.jsonPrimitive.content)
        assertEquals(10, out["usage"]!!.jsonObject["input_tokens"]!!.jsonPrimitive.int)
        assertEquals(5, out["usage"]!!.jsonObject["output_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun toolCallsBecomeToolUseBlocks() {
        val out = conv("""{"id":"r1","model":"m","choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"get","arguments":"{\"x\":1}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}""")
        val block = out["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_use", block["type"]!!.jsonPrimitive.content)
        assertEquals("c1", block["id"]!!.jsonPrimitive.content)
        assertEquals("get", block["name"]!!.jsonPrimitive.content)
        assertEquals(1, block["input"]!!.jsonObject["x"]!!.jsonPrimitive.int)
        assertEquals("tool_use", out["stop_reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun finishReasonMapping() {
        fun stopReason(fr: String) = conv("""{"choices":[{"message":{"content":"x"},"finish_reason":"$fr"}],"usage":{}}""")["stop_reason"]!!.jsonPrimitive.content
        assertEquals("end_turn", stopReason("stop"))
        assertEquals("max_tokens", stopReason("length"))
        assertEquals("tool_use", stopReason("tool_calls"))
    }
}
