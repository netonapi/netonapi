package logic.protocol.codec

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiToAnthropicRequestTest {
    private val json = Json { }
    private fun conv(body: String) = json.parseToJsonElement(OpenAiToAnthropicRequest.convert(body)).jsonObject

    @Test
    fun systemMessageBecomesSystemField() {
        val out = conv("""{"model":"claude","messages":[{"role":"system","content":"be brief"},{"role":"user","content":"hi"}]}""")
        assertEquals("be brief", out["system"]!!.jsonPrimitive.content)
        val msgs = out["messages"]!!.jsonArray
        assertEquals(1, msgs.size)
        assertEquals("user", msgs[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun maxTokensDefaultedWhenMissing() {
        val out = conv("""{"model":"c","messages":[{"role":"user","content":"hi"}]}""")
        assertTrue(out["max_tokens"]!!.jsonPrimitive.int > 0)
    }

    @Test
    fun maxTokensPreserved() {
        val out = conv("""{"model":"c","max_tokens":222,"messages":[{"role":"user","content":"hi"}]}""")
        assertEquals(222, out["max_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun assistantToolCallsBecomeToolUseBlocks() {
        val body = """{"model":"c","messages":[
          {"role":"user","content":"weather?"},
          {"role":"assistant","content":null,"tool_calls":[{"id":"t1","type":"function","function":{"name":"get","arguments":"{\"city\":\"SF\"}"}}]},
          {"role":"tool","tool_call_id":"t1","content":"sunny"}
        ]}"""
        val msgs = conv(body)["messages"]!!.jsonArray
        val asstBlocks = msgs[1].jsonObject["content"]!!.jsonArray
        val toolUse = asstBlocks[0].jsonObject
        assertEquals("tool_use", toolUse["type"]!!.jsonPrimitive.content)
        assertEquals("get", toolUse["name"]!!.jsonPrimitive.content)
        assertEquals("SF", toolUse["input"]!!.jsonObject["city"]!!.jsonPrimitive.content)
        // tool 消息 → user 角色的 tool_result 块
        val toolMsg = msgs[2].jsonObject
        assertEquals("user", toolMsg["role"]!!.jsonPrimitive.content)
        val tr = toolMsg["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", tr["type"]!!.jsonPrimitive.content)
        assertEquals("t1", tr["tool_use_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolsAndStopConverted() {
        val out = conv("""{"model":"c","stop":["END"],"tools":[{"type":"function","function":{"name":"f","description":"d","parameters":{"type":"object"}}}],"messages":[{"role":"user","content":"x"}]}""")
        val tool = out["tools"]!!.jsonArray[0].jsonObject
        assertEquals("f", tool["name"]!!.jsonPrimitive.content)
        assertTrue(tool.containsKey("input_schema"))
        assertEquals("END", out["stop_sequences"]!!.jsonArray[0].jsonPrimitive.content)
    }
}
