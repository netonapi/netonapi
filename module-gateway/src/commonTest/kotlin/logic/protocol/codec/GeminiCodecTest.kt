package logic.protocol.codec

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeminiCodecTest {
    private val json = Json { }
    private fun o(s: String) = json.parseToJsonElement(s).jsonObject

    // ── Gemini → OpenAI 请求 ──
    @Test fun geminiReqToOpenAi() {
        val g = """{"model":"gpt-x","contents":[{"role":"user","parts":[{"text":"hi"}]},{"role":"model","parts":[{"text":"yo"}]}],
          "systemInstruction":{"parts":[{"text":"brief"}]},
          "generationConfig":{"maxOutputTokens":100,"temperature":0.5,"stopSequences":["END"]}}"""
        val out = o(GeminiToOpenAiRequest.convert(g))
        assertEquals("gpt-x", out["model"]!!.jsonPrimitive.content)
        assertEquals(100, out["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("END", out["stop"]!!.jsonArray[0].jsonPrimitive.content)
        val msgs = out["messages"]!!.jsonArray
        assertEquals("system", msgs[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("brief", msgs[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", msgs[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("assistant", msgs[2].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test fun geminiFunctionCallToToolCalls() {
        val g = """{"model":"m","contents":[{"role":"model","parts":[{"functionCall":{"name":"get","args":{"x":1}}}]},{"role":"user","parts":[{"functionResponse":{"name":"get","response":{"r":"ok"}}}]}]}"""
        val msgs = o(GeminiToOpenAiRequest.convert(g))["messages"]!!.jsonArray
        val call = msgs[0].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("get", call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("tool", msgs[1].jsonObject["role"]!!.jsonPrimitive.content)
    }

    // ── OpenAI → Gemini 请求 ──
    @Test fun openaiReqToGemini() {
        val oa = """{"model":"g","max_tokens":50,"temperature":0.3,"messages":[{"role":"system","content":"brief"},{"role":"user","content":"hi"},{"role":"assistant","content":"yo"}]}"""
        val out = o(OpenAiToGeminiRequest.convert(oa))
        assertEquals("brief", out["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(50, out["generationConfig"]!!.jsonObject["maxOutputTokens"]!!.jsonPrimitive.int)
        val contents = out["contents"]!!.jsonArray
        assertEquals("user", contents[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("model", contents[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("hi", contents[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    // ── Gemini → OpenAI 响应 ──
    @Test fun geminiRespToOpenAi() {
        val g = """{"candidates":[{"content":{"parts":[{"text":"hello"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5}}"""
        val out = o(GeminiToOpenAiResponse.convert(g))
        assertEquals("chat.completion", out["object"]!!.jsonPrimitive.content)
        val choice = out["choices"]!!.jsonArray[0].jsonObject
        assertEquals("hello", choice["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("stop", choice["finish_reason"]!!.jsonPrimitive.content)
        assertEquals(10, out["usage"]!!.jsonObject["prompt_tokens"]!!.jsonPrimitive.int)
        assertEquals(5, out["usage"]!!.jsonObject["completion_tokens"]!!.jsonPrimitive.int)
    }

    // ── OpenAI → Gemini 响应 ──
    @Test fun openaiRespToGemini() {
        val oa = """{"choices":[{"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}"""
        val out = o(OpenAiToGeminiResponse.convert(oa))
        val cand = out["candidates"]!!.jsonArray[0].jsonObject
        assertEquals("hello", cand["content"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("model", cand["content"]!!.jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("STOP", cand["finishReason"]!!.jsonPrimitive.content)
        assertEquals(10, out["usageMetadata"]!!.jsonObject["promptTokenCount"]!!.jsonPrimitive.int)
    }

    // ── Gemini → OpenAI 流式 ──
    @Test fun geminiStreamToOpenAi() {
        val sm = GeminiToOpenAiStream("gpt-x")
        val out = StringBuilder()
        out.append(sm.feed("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"He\"}],\"role\":\"model\"}}]}\n\n"))
        out.append(sm.feed("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"llo\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":3,\"candidatesTokenCount\":2}}\n\n"))
        out.append(sm.finish())
        val body = out.toString()
        assertTrue(body.contains("chat.completion.chunk"))
        assertTrue(body.contains("\"content\":\"He\""))
        assertTrue(body.contains("\"finish_reason\":\"stop\""))
        assertTrue(body.contains("[DONE]"))
    }

    // ── OpenAI → Gemini 流式 ──
    @Test fun openaiStreamToGemini() {
        val sm = OpenAiToGeminiStream("g")
        val out = StringBuilder()
        out.append(sm.feed("data: {\"choices\":[{\"delta\":{\"content\":\"He\"}}]}\n\n"))
        out.append(sm.feed("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}\n\n"))
        out.append(sm.finish())
        val body = out.toString()
        assertTrue(body.contains("candidates"), body)
        assertTrue(body.contains("\"text\":\"He\""))
        assertTrue(body.contains("\"finishReason\":\"STOP\""))
        assertTrue(body.contains("usageMetadata"))
    }
}
