package logic

import model.Channel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 跨协议转发的上游路径：请求体已经转成上游协议，路径也必须是上游协议的。
 * 回归：Anthropic 格式（/v1/messages）调 OpenAI 兼容上游时，曾把 Chat 格式的请求体发到上游的 /v1/messages。
 */
class CrossProtocolPathTest {

    private val openaiChannel = Channel(id = 1, name = "c", type = "openai_compatible", baseUrl = "https://u.example.com/v1")
    private val geminiChannel = Channel(id = 2, name = "g", type = "gemini", baseUrl = "https://g.example.com/v1beta")

    @Test fun anthropicInboundToOpenAiUpstreamUsesChatCompletions() {
        val up = RelayEngine.adapterForChannel("openai_compatible")!!
        assertEquals("/v1/chat/completions", RelayEngine.crossProtocolPathFor(up, openaiChannel, "/v1/messages", "gpt-4.1-mini", stream = false))
        assertEquals("/v1/chat/completions", RelayEngine.crossProtocolPathFor(up, openaiChannel, "/v1/messages", "gpt-4.1-mini", stream = true))
    }

    @Test fun geminiInboundToOpenAiUpstreamUsesChatCompletions() {
        val up = RelayEngine.adapterForChannel("openai_compatible")!!
        assertEquals("/v1/chat/completions", RelayEngine.crossProtocolPathFor(up, openaiChannel, "/v1beta/models/gpt-4.1-mini:generateContent", "gpt-4.1-mini", stream = false))
    }

    @Test fun nonOpenAiUpstreamsKeepTheirOwnPaths() {
        val gemini = RelayEngine.adapterForChannel("gemini")!!
        assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", RelayEngine.crossProtocolPathFor(gemini, geminiChannel, "/v1/messages", "gemini-2.5-flash", stream = false))
        val anthropic = RelayEngine.adapterForChannel("anthropic")!!
        assertEquals("/v1/messages", RelayEngine.crossProtocolPathFor(anthropic, openaiChannel, "/v1/chat/completions", "claude-sonnet-4", stream = false))
    }
}
