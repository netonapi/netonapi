package logic.protocol.codec

import logic.protocol.GeminiRequestConverter
import logic.protocol.GeminiRequestOptions
import logic.protocol.ProtocolCodecs
import logic.protocol.SignatureDiagnostics

/*
 * gateway 原有编解码器已由 neton-ai 协议层取代（neton-docs spec/ai.md §12 第 4 步）。
 * 这些同名薄壳把既有测试指向新实现，让它们成为行为等价的回归测试。
 */

object AnthropicToOpenAiRequest { fun convert(b: String) = ProtocolCodecs.requestToHub("anthropic")(b) }
object GeminiToOpenAiRequest { fun convert(b: String) = ProtocolCodecs.requestToHub("gemini")(b) }
object OpenAiToAnthropicRequest { fun convert(b: String) = ProtocolCodecs.requestFromHub("anthropic")(b) }
object OpenAiToGeminiRequest {
    fun convert(b: String) = ProtocolCodecs.requestFromHub("gemini")(b)
    fun convert(b: String, options: GeminiRequestOptions, diagnostics: SignatureDiagnostics) = GeminiRequestConverter.convert(b, options, diagnostics)
}
object AnthropicToOpenAiResponse { fun convert(b: String) = ProtocolCodecs.responseToHub("anthropic")(b) }
object GeminiToOpenAiResponse { fun convert(b: String) = ProtocolCodecs.responseToHub("gemini")(b) }
object OpenAiToAnthropicResponse { fun convert(b: String) = ProtocolCodecs.responseFromHub("anthropic")(b) }
object OpenAiToGeminiResponse { fun convert(b: String) = ProtocolCodecs.responseFromHub("gemini")(b) }

open class StageShim(private val stage: logic.protocol.StreamStage) {
    fun feed(chunk: String): String = stage.feed(chunk)
    fun finish(): String = stage.finish()
}
class AnthropicToOpenAiStream(model: String) : StageShim(ProtocolCodecs.streamToHub("anthropic", model)!!)
class GeminiToOpenAiStream(model: String) : StageShim(ProtocolCodecs.streamToHub("gemini", model)!!)
class OpenAiToAnthropicStream(model: String) : StageShim(ProtocolCodecs.streamFromHub("anthropic", model)!!)
class OpenAiToGeminiStream(model: String) : StageShim(ProtocolCodecs.streamFromHub("gemini", model)!!)
