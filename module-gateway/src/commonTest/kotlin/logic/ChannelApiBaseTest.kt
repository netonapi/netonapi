package logic

import model.Channel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 运营填 Base URL 时带不带 /v1 都常见（OpenAI SDK 的 base_url 带，new-api 的渠道地址不带）。上游路径本身以 /v1 开头，
 * 不去掉就拼成 …/v1/v1/chat/completions（2026-10-27 线上 4 条真实渠道全部 404）。
 */
class ChannelApiBaseTest {

    private fun base(type: String, url: String) = Channel(name = "c", type = type, baseUrl = url).apiBase()

    @Test fun openAiCompatibleAcceptsBothForms() {
        assertEquals("https://ycyapi.cn", base("openai_compatible", "https://ycyapi.cn/v1"))
        assertEquals("https://ycyapi.cn", base("openai_compatible", "https://ycyapi.cn/v1/"))
        assertEquals("https://ycyapi.cn", base("openai_compatible", "https://ycyapi.cn"))
        assertEquals("https://ycyapi.cn", base("openai_compatible", " https://ycyapi.cn/ "))
    }

    @Test fun prefixedGatewaysKeepTheirPrefix() {
        assertEquals("https://example.com/openai", base("openai_compatible", "https://example.com/openai/v1"))
    }

    @Test fun anthropicAndGeminiStripTheirVersionSegment() {
        assertEquals("https://api.anthropic.com", base("anthropic", "https://api.anthropic.com/v1"))
        assertEquals("https://generativelanguage.googleapis.com", base("gemini", "https://generativelanguage.googleapis.com/v1beta"))
    }

    @Test fun otherTypesAreLeftAsIs() {
        assertEquals("https://res.openai.azure.com", base("azure_openai", "https://res.openai.azure.com/"))
        assertEquals("https://bedrock-runtime.us-east-1.amazonaws.com/v1", base("bedrock", "https://bedrock-runtime.us-east-1.amazonaws.com/v1"))
    }
}
