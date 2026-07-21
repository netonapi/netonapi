package logic.protocol.codec

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class AnthropicToOpenAiResponseTest {
    private val json = Json { }
    private fun conv(body: String) = json.parseToJsonElement(AnthropicToOpenAiResponse.convert(body)).jsonObject

    @Test
    fun textResponse() {
        val out = conv("""{"id":"m1","model":"claude","content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}""")
        assertEquals("chat.completion", out["object"]!!.jsonPrimitive.content)
        val choice = out["choices"]!!.jsonArray[0].jsonObject
        assertEquals("hello", choice["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("stop", choice["finish_reason"]!!.jsonPrimitive.content)
        assertEquals(10, out["usage"]!!.jsonObject["prompt_tokens"]!!.jsonPrimitive.int)
        assertEquals(5, out["usage"]!!.jsonObject["completion_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun toolUseBecomesToolCalls() {
        val out = conv("""{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"get","input":{"x":1}}],"stop_reason":"tool_use","usage":{"input_tokens":1,"output_tokens":1}}""")
        val choice = out["choices"]!!.jsonArray[0].jsonObject
        val call = choice["message"]!!.jsonObject["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("t1", call["id"]!!.jsonPrimitive.content)
        assertEquals("get", call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("tool_calls", choice["finish_reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun stopReasonMapping() {
        fun fr(sr: String) = conv("""{"content":[{"type":"text","text":"x"}],"stop_reason":"$sr","usage":{}}""")["choices"]!!.jsonArray[0].jsonObject["finish_reason"]!!.jsonPrimitive.content
        assertEquals("stop", fr("end_turn"))
        assertEquals("length", fr("max_tokens"))
        assertEquals("tool_calls", fr("tool_use"))
    }
}
