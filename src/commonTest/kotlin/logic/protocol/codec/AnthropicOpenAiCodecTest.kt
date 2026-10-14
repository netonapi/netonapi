package logic.protocol.codec

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicOpenAiCodecTest {
    private val json = Json { }
    private fun obj(s: String) = json.parseToJsonElement(s).jsonObject

    @Test
    fun anthropicRequest_toolResultsPrecedeUserText_andServerToolsDropped() {
        val body = """{"model":"m","max_tokens":10,"system":[{"type":"text","text":"sys","cache_control":{"type":"ephemeral"}}],
          "messages":[
            {"role":"assistant","content":[{"type":"thinking","thinking":"hmm","signature":"x"},{"type":"tool_use","id":"t1","name":"ls","input":{"p":"/"}}]},
            {"role":"user","content":[{"type":"text","text":"go on"},{"type":"tool_result","tool_use_id":"t1","content":[{"type":"text","text":"a.txt"}]}]}
          ],
          "tools":[{"name":"ls","input_schema":{"type":"object"}},{"type":"web_search_20250305","name":"web_search"}],
          "tool_choice":{"type":"auto","disable_parallel_tool_use":true},
          "thinking":{"type":"enabled","budget_tokens":10000},"metadata":{"user_id":"u9"}}"""
        val o = obj(AnthropicToOpenAiRequest.convert(body))
        val msgs = o["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "assistant", "tool", "user"), msgs.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("t1", msgs[2]["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("a.txt", msgs[2]["content"]!!.jsonPrimitive.content)
        assertEquals("ls", msgs[1]["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(1, o["tools"]!!.jsonArray.size)
        assertEquals(false, o["parallel_tool_calls"]!!.jsonPrimitive.boolean)
        assertEquals("high", o["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("u9", o["user"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropicRequest_urlImageBecomesImageUrl() {
        val body = """{"model":"m","max_tokens":1,"messages":[{"role":"user","content":[{"type":"text","text":"what"},{"type":"image","source":{"type":"url","url":"https://x/a.png"}}]}]}"""
        val parts = obj(AnthropicToOpenAiRequest.convert(body))["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("https://x/a.png", parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun openAiRequest_partsImagesToolsAndMerging() {
        val body = """{"model":"m","max_completion_tokens":300,"reasoning_effort":"low","user":"u1","temperature":0.2,
          "messages":[
            {"role":"system","content":[{"type":"text","text":"be brief"}]},
            {"role":"user","content":[{"type":"text","text":"see"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,QUJD"}}]},
            {"role":"assistant","content":[{"type":"text","text":"calling"}],"tool_calls":[{"id":"c1","type":"function","function":{"name":"f","arguments":"{\"a\":1}"}}]},
            {"role":"tool","tool_call_id":"c1","content":"r1"},
            {"role":"tool","tool_call_id":"c2","content":[{"type":"text","text":"r2"}]},
            {"role":"user","content":"and?"}
          ],"tools":[{"type":"function","function":{"name":"f","parameters":{"type":"object"}}}],"tool_choice":"none","parallel_tool_calls":false}"""
        val o = obj(OpenAiToAnthropicRequest.convert(body))
        assertEquals("be brief", o["system"]!!.jsonPrimitive.content)
        assertEquals(2048, o["max_tokens"]!!.jsonPrimitive.int)  // 预算 1024 > 300 → 抬到预算 + 1024
        assertEquals(1024, o["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int)
        assertNull(o["temperature"])  // 开了 thinking 不带 temperature
        assertEquals("u1", o["metadata"]!!.jsonObject["user_id"]!!.jsonPrimitive.content)
        val msgs = o["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), msgs.map { it["role"]!!.jsonPrimitive.content })
        val img = msgs[0]["content"]!!.jsonArray[1].jsonObject
        assertEquals("image", img["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", img["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content)
        assertEquals("tool_use", msgs[1]["content"]!!.jsonArray[1].jsonObject["type"]!!.jsonPrimitive.content)
        val merged = msgs[2]["content"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("tool_result", "tool_result", "text"), merged)
        assertEquals("r2", msgs[2]["content"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("none", o["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        // Anthropic 的 tool_choice=none 只有 type 字段；disable_parallel_tool_use 只属于 auto/any/tool
        assertNull(o["tool_choice"]!!.jsonObject["disable_parallel_tool_use"])
    }

    @Test
    fun responses_carryReasoningAndCacheUsageBothWays() {
        val fromOpenAi = obj(OpenAiToAnthropicResponse.convert("""{"id":"x","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"hi","reasoning_content":"why"},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":40}}}"""))
        val blocks = fromOpenAi["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("thinking", blocks[0]["type"]!!.jsonPrimitive.content)
        assertEquals("why", blocks[0]["thinking"]!!.jsonPrimitive.content)
        assertEquals(60, fromOpenAi["usage"]!!.jsonObject["input_tokens"]!!.jsonPrimitive.int)
        assertEquals(40, fromOpenAi["usage"]!!.jsonObject["cache_read_input_tokens"]!!.jsonPrimitive.int)

        val fromAnthropic = obj(AnthropicToOpenAiResponse.convert("""{"id":"m1","model":"c","content":[{"type":"thinking","thinking":"t","signature":"s"},{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":10,"cache_read_input_tokens":30,"cache_creation_input_tokens":5,"output_tokens":2}}"""))
        val msg = fromAnthropic["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
        assertEquals("t", msg["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals(45, fromAnthropic["usage"]!!.jsonObject["prompt_tokens"]!!.jsonPrimitive.int)
        assertEquals(30, fromAnthropic["usage"]!!.jsonObject["prompt_tokens_details"]!!.jsonObject["cached_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun openAiStream_thinkingThenTextThenTool_isWellFormedAnthropicSequence() {
        val s = OpenAiToAnthropicStream("m")
        val out = StringBuilder()
        fun chunk(delta: String, finish: String = "null") = "data: {\"id\":\"c\",\"choices\":[{\"index\":0,\"delta\":$delta,\"finish_reason\":$finish}]}\n\n"
        out.append(s.feed(chunk("""{"role":"assistant","reasoning_content":"th"}""")))
        out.append(s.feed(chunk("""{"content":"he"}""")))
        out.append(s.feed(chunk("""{"content":"llo"}""")))
        out.append(s.feed(chunk("""{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"f","arguments":"{\"a\""}}]}""")))
        out.append(s.feed(chunk("""{"tool_calls":[{"index":0,"function":{"arguments":":1}"}}]}""", "\"tool_calls\"")))
        out.append(s.feed("data: {\"id\":\"c\",\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}\n\ndata: [DONE]\n\n"))
        out.append(s.finish())
        val events = Regex("event: (\\w+)").findAll(out).map { it.groupValues[1] }.toList()
        assertEquals(listOf(
            "message_start",
            "content_block_start", "content_block_delta", "content_block_delta", "content_block_stop",   // thinking + signature
            "content_block_start", "content_block_delta", "content_block_delta", "content_block_stop",   // text
            "content_block_start", "content_block_delta", "content_block_delta", "content_block_stop",   // tool_use
            "message_delta", "message_stop",
        ), events)
        val text = out.toString()
        assertTrue(text.contains("\"type\":\"thinking_delta\",\"thinking\":\"th\""))
        assertTrue(text.contains("\"type\":\"signature_delta\""))
        assertTrue(text.contains("\"partial_json\":\":1}\""))
        assertTrue(text.contains("\"stop_reason\":\"tool_use\""))
        assertTrue(text.contains("\"input_tokens\":7"))
    }

    @Test
    fun anthropicStream_thinkingBecomesReasoningContent_andUsageMerged() {
        val s = AnthropicToOpenAiStream("m")
        val out = StringBuilder()
        fun ev(type: String, body: String) = "event: $type\ndata: {\"type\":\"$type\",$body}\n\n"
        out.append(s.feed(ev("message_start", """"message":{"usage":{"input_tokens":10,"cache_read_input_tokens":20}}""")))
        out.append(s.feed(ev("content_block_start", """"index":0,"content_block":{"type":"thinking","thinking":""}""")))
        out.append(s.feed(ev("content_block_delta", """"index":0,"delta":{"type":"thinking_delta","thinking":"deep"}""")))
        out.append(s.feed(ev("content_block_delta", """"index":1,"delta":{"type":"text_delta","text":"hi"}""")))
        out.append(s.feed(ev("message_delta", """"delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4}""")))
        out.append(s.finish())
        val text = out.toString()
        assertTrue(text.contains("\"reasoning_content\":\"deep\""))
        assertTrue(text.contains("\"content\":\"hi\""))
        assertTrue(text.contains("\"prompt_tokens\":30"))
        assertTrue(text.contains("\"cached_tokens\":20"))
        assertTrue(text.contains("\"completion_tokens\":4"))
        assertTrue(text.endsWith("data: [DONE]\n\n"))
        assertFalse(text.contains("\"finish_reason\":\"stop\"}}]}\n\ndata: {"), "finish 块只能在最后")
    }

    @Test
    fun anthropicRequest_adaptiveThinkingUsesOutputConfigEffort() {
        val body = """{"model":"m","max_tokens":1,"messages":[{"role":"user","content":"hi"},{"role":"system","content":"mid"}],"thinking":{"type":"adaptive"},"output_config":{"effort":"high"}}"""
        val o = obj(AnthropicToOpenAiRequest.convert(body))
        assertEquals("high", o["reasoning_effort"]!!.jsonPrimitive.content)
        // mid-conversation system（Claude Code 的 beta）原样保留为 OpenAI 的 system 消息
        assertEquals("system", o["messages"]!!.jsonArray[1].jsonObject["role"]!!.jsonPrimitive.content)
        val adaptiveOnly = obj(AnthropicToOpenAiRequest.convert("""{"model":"m","max_tokens":1,"messages":[],"thinking":{"type":"adaptive"}}"""))
        assertEquals("medium", adaptiveOnly["reasoning_effort"]!!.jsonPrimitive.content)
    }
}
