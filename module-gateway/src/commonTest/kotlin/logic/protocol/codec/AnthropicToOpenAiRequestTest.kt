package logic.protocol.codec

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnthropicToOpenAiRequestTest {
    private val json = Json { }
    private fun conv(body: String) = json.parseToJsonElement(AnthropicToOpenAiRequest.convert(body)).jsonObject

    @Test
    fun plainTextConversation() {
        val out = conv("""{"model":"gpt-x","max_tokens":100,"messages":[{"role":"user","content":"hi"}]}""")
        val msgs = out["messages"]!!.jsonArray
        assertEquals("user", msgs[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("hi", msgs[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(100, out["max_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun systemStringBecomesLeadingMessage() {
        val out = conv("""{"model":"m","max_tokens":10,"system":"be brief","messages":[{"role":"user","content":"hi"}]}""")
        val msgs = out["messages"]!!.jsonArray
        assertEquals("system", msgs[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("be brief", msgs[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun textBlocksJoin() {
        val out = conv("""{"model":"m","max_tokens":10,"messages":[{"role":"user","content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}]}""")
        assertEquals("a\nb", out["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun imageBlockToDataUrl() {
        val out = conv("""{"model":"m","max_tokens":10,"messages":[{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}}]}]}""")
        val content = out["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        val img = content[0].jsonObject
        assertEquals("image_url", img["type"]!!.jsonPrimitive.content)
        assertTrue(img["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.startsWith("data:image/png;base64,AAAA"))
    }

    @Test
    fun toolUseAndToolResultRoundtrip() {
        val body = """{"model":"m","max_tokens":10,"messages":[
          {"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"get_weather","input":{"city":"SF"}}]},
          {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"sunny"}]}
        ]}"""
        val msgs = conv(body)["messages"]!!.jsonArray
        val asst = msgs[0].jsonObject
        val call = asst["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("t1", call["id"]!!.jsonPrimitive.content)
        assertEquals("get_weather", call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content.contains("SF"))
        val tool = msgs[1].jsonObject
        assertEquals("tool", tool["role"]!!.jsonPrimitive.content)
        assertEquals("t1", tool["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolsSchemaAndChoice() {
        val body = """{"model":"m","max_tokens":10,"tool_choice":{"type":"tool","name":"f"},"tools":[{"name":"f","description":"d","input_schema":{"type":"object","properties":{}}}],"messages":[{"role":"user","content":"hi"}]}"""
        val out = conv(body)
        val tool = out["tools"]!!.jsonArray[0].jsonObject
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)
        assertEquals("f", tool["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(tool["function"]!!.jsonObject.containsKey("parameters"))
        val choice = out["tool_choice"]!!.jsonObject
        assertEquals("f", choice["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun stopSequencesRenamed() {
        val out = conv("""{"model":"m","max_tokens":10,"stop_sequences":["END"],"messages":[{"role":"user","content":"x"}]}""")
        assertEquals("END", out["stop"]!!.jsonArray[0].jsonPrimitive.content)
    }
}
