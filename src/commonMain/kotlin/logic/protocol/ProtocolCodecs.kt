package logic.protocol

import logic.protocol.codec.*

/**
 * 协议 codec 注册表（枢纽模型：OpenAI ChatCompletions 为内部规范格式）。
 * 跨协议 = inbound→hub→upstream 组合；openai 端各段为恒等。
 * 三协议只需 2×N 组转换，Gemini↔Anthropic 经 hub 免费组合。
 */
object ProtocolCodecs {

    fun identityStr(): (String) -> String = { it }

    /** 请求：某协议 body → OpenAI hub body。openai=恒等。 */
    fun requestToHub(protocol: String): (String) -> String = when (protocol) {
        "openai" -> identityStr()
        "anthropic" -> { b -> AnthropicToOpenAiRequest.convert(b) }
        "gemini" -> { b -> GeminiToOpenAiRequest.convert(b) }
        else -> identityStr()
    }

    /** 请求：OpenAI hub body → 某协议 body。 */
    fun requestFromHub(protocol: String): (String) -> String = when (protocol) {
        "openai" -> identityStr()
        "anthropic" -> { b -> OpenAiToAnthropicRequest.convert(b) }
        "gemini" -> { b -> OpenAiToGeminiRequest.convert(b) }
        else -> identityStr()
    }

    /** 响应：某协议 body → OpenAI hub body（上游原生 → hub）。 */
    fun responseToHub(protocol: String): (String) -> String = when (protocol) {
        "openai" -> identityStr()
        "anthropic" -> { b -> AnthropicToOpenAiResponse.convert(b) }
        "gemini" -> { b -> GeminiToOpenAiResponse.convert(b) }
        else -> identityStr()
    }

    /** 响应：OpenAI hub body → 某协议 body（hub → 入站）。 */
    fun responseFromHub(protocol: String): (String) -> String = when (protocol) {
        "openai" -> identityStr()
        "anthropic" -> { b -> OpenAiToAnthropicResponse.convert(b) }
        "gemini" -> { b -> OpenAiToGeminiResponse.convert(b) }
        else -> identityStr()
    }

    /** 流式：某协议 SSE → OpenAI chunk 流。null=恒等透传。 */
    fun streamToHub(protocol: String, model: String): StreamStage? = when (protocol) {
        "openai" -> null
        "anthropic" -> AnthropicToOpenAiStream(model).let { sm -> StreamStage({ sm.feed(it) }, { sm.finish() }) }
        "gemini" -> GeminiToOpenAiStream(model).let { sm -> StreamStage({ sm.feed(it) }, { sm.finish() }) }
        else -> null
    }

    /** 流式：OpenAI chunk 流 → 某协议 SSE。null=恒等透传。 */
    fun streamFromHub(protocol: String, model: String): StreamStage? = when (protocol) {
        "openai" -> null
        "anthropic" -> OpenAiToAnthropicStream(model).let { sm -> StreamStage({ sm.feed(it) }, { sm.finish() }) }
        "gemini" -> OpenAiToGeminiStream(model).let { sm -> StreamStage({ sm.feed(it) }, { sm.finish() }) }
        else -> null
    }
}

/** 单个流式转换阶段（feed/finish）。 */
class StreamStage(val feed: (String) -> String, val finish: () -> String)
