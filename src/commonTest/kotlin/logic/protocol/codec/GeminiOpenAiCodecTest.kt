package logic.protocol.codec

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeminiOpenAiCodecTest {
    private val json = Json { }
    private fun obj(s: String) = json.parseToJsonElement(s).jsonObject

    @Test
    fun geminiRequest_imagesToolsAndThinking() {
        val body = """{"model":"g","contents":[
            {"role":"user","parts":[{"text":"look"},{"inlineData":{"mimeType":"image/png","data":"QUJD"}}]},
            {"role":"model","parts":[{"functionCall":{"name":"get","args":{"q":1}}}]},
            {"role":"user","parts":[{"functionResponse":{"name":"get","response":{"r":2}}}]}
          ],"tools":[{"functionDeclarations":[{"name":"get","parameters":{"type":"object"}}]}],
          "toolConfig":{"functionCallingConfig":{"mode":"ANY","allowedFunctionNames":["get"]}},
          "generationConfig":{"maxOutputTokens":50,"thinkingConfig":{"thinkingBudget":9000},"responseMimeType":"application/json"}}"""
        val o = obj(GeminiToOpenAiRequest.convert(body))
        val msgs = o["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "tool"), msgs.map { it["role"]!!.jsonPrimitive.content })
        assertTrue(msgs[0]["content"]!!.jsonArray[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.startsWith("data:image/png;base64,QUJD"))
        assertEquals("get", msgs[2]["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("high", o["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("json_object", o["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("get", o["tool_choice"]!!.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun openAiRequest_toolResponsesMergeAndImagesBecomeInlineData() {
        val body = """{"model":"m","messages":[
            {"role":"user","content":[{"type":"text","text":"a"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,Zm9v"}},{"type":"image_url","image_url":{"url":"https://x/y.png"}}]},
            {"role":"assistant","tool_calls":[{"id":"c1","type":"function","function":{"name":"f","arguments":"{}"}},{"id":"c2","type":"function","function":{"name":"g","arguments":"{}"}}]},
            {"role":"tool","tool_call_id":"c1","content":"{\"ok\":true}"},
            {"role":"tool","tool_call_id":"c2","content":"plain"}
          ],"reasoning_effort":"medium","response_format":{"type":"json_schema","json_schema":{"schema":{"type":"object"}}}}"""
        val o = obj(OpenAiToGeminiRequest.convert(body))
        val contents = o["contents"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, contents.size)
        val p0 = contents[0]["parts"]!!.jsonArray
        assertEquals("image/jpeg", p0[1].jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content)
        assertEquals("https://x/y.png", p0[2].jsonObject["fileData"]!!.jsonObject["fileUri"]!!.jsonPrimitive.content)
        val responses = contents[2]["parts"]!!.jsonArray.map { it.jsonObject["functionResponse"]!!.jsonObject }
        assertEquals(listOf("f", "g"), responses.map { it["name"]!!.jsonPrimitive.content })
        assertEquals(true, responses[0]["response"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean)
        assertEquals("plain", responses[1]["response"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        val gc = o["generationConfig"]!!.jsonObject
        assertEquals(8192, gc["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.int)
        assertEquals("object", gc["responseSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun geminiStream_functionCallAndThought() {
        val s = GeminiToOpenAiStream("m")
        val out = StringBuilder()
        out.append(s.feed("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"mm\",\"thought\":true},{\"text\":\"hi\"}],\"role\":\"model\"},\"index\":0}]}\n\n"))
        out.append(s.feed("data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"f\",\"args\":{\"a\":1}}}],\"role\":\"model\"},\"finishReason\":\"STOP\",\"index\":0}],\"usageMetadata\":{\"promptTokenCount\":3,\"candidatesTokenCount\":2,\"thoughtsTokenCount\":5}}\n\n"))
        out.append(s.finish())
        val t = out.toString()
        assertTrue(t.contains("\"reasoning_content\":\"mm\""))
        assertTrue(t.contains("\"content\":\"hi\""))
        assertTrue(t.contains("\"arguments\":\"{\\\"a\\\":1}\""))
        assertTrue(t.contains("\"finish_reason\":\"tool_calls\""))
        assertTrue(t.contains("\"completion_tokens\":7"))
    }

    @Test
    fun openAiStream_toolArgsAssembledIntoOneFunctionCall() {
        val s = OpenAiToGeminiStream("m")
        val out = StringBuilder()
        fun chunk(delta: String, finish: String = "null") = "data: {\"choices\":[{\"index\":0,\"delta\":$delta,\"finish_reason\":$finish}]}\n\n"
        out.append(s.feed(chunk("""{"reasoning_content":"r"}""")))
        out.append(s.feed(chunk("""{"tool_calls":[{"index":0,"id":"c1","function":{"name":"f","arguments":"{\"a\""}}]}""")))
        out.append(s.feed(chunk("""{"tool_calls":[{"index":0,"function":{"arguments":":2}"}}]}""", "\"tool_calls\"")))
        out.append(s.finish())
        val t = out.toString()
        assertTrue(t.contains("\"thought\":true"))
        assertTrue(t.contains("\"functionCall\":{\"id\":\"c1\",\"name\":\"f\",\"args\":{\"a\":2}}"))
        assertTrue(t.indexOf("functionCall") < t.indexOf("\"finishReason\""))
    }
}
