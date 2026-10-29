package logic.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import neton.ai.protocol.convert.ProtocolConverter
import neton.ai.protocol.ir.WireFormat

/**
 * 协议转换（实现在 neton-ai 协议层，neton-docs spec/ai.md）。
 *
 * gateway 仍以 OpenAI ChatCompletions 为内部枢纽：跨协议 = inbound→hub→upstream，签名状态引用
 * （[logic.ThoughtSignatureVault]）在 hub 上工作。openai 端各段为恒等。
 */
object ProtocolCodecs {
    private val json = Json { ignoreUnknownKeys = true }

    /** gateway 协议 id → 报文格式。 */
    fun formatOf(protocol: String): WireFormat? = when (protocol) {
        "openai" -> WireFormat.OPENAI_CHAT
        "anthropic" -> WireFormat.ANTHROPIC
        "gemini" -> WireFormat.GEMINI
        "bedrock_converse" -> WireFormat.BEDROCK_CONVERSE
        ResponsesBridge.PROTOCOL -> WireFormat.OPENAI_RESPONSES
        else -> null
    }

    fun identityStr(): (String) -> String = { it }

    private fun parse(body: String): JsonObject = json.parseToJsonElement(body).jsonObject

    private fun request(from: WireFormat, to: WireFormat): (String) -> String =
        { b -> ProtocolConverter.convertRequest(parse(b), from, to).value.toString() }

    /**
     * 按损耗策略转换请求；诊断追加到 [diagnostics]。违反策略时抛
     * [neton.ai.protocol.diag.ConversionRejectedException]（调用方据此换下一个渠道）。
     */
    fun convertRequest(
        body: String, from: String, to: String,
        policy: neton.ai.protocol.diag.LossPolicy,
        diagnostics: MutableList<neton.ai.protocol.diag.Diagnostic>,
    ): String {
        val f = formatOf(from) ?: return body
        val t = formatOf(to) ?: return body
        if (f == t) return body
        val r = ProtocolConverter.convertRequest(parse(body), f, t, policy)
        diagnostics += r.diagnostics
        return r.value.toString()
    }

    /**
     * 把推理意图写进某协议的请求体（模型名推理尾缀，§4.3）：解码 → 设置模型与推理 → 按原协议编码。
     * Gemini 报文本来没有 model 字段，gateway 放在 body 里给路由用，这里写回。
     */
    fun withReasoning(protocol: String, body: String, model: String, reasoning: neton.ai.protocol.ir.ReasoningOptions): String {
        val f = formatOf(protocol) ?: return body
        val codec = neton.ai.protocol.codec.ProtocolCodecs.of(f)
        val ir = codec.decodeRequest(parse(body))
        val out = codec.encodeRequest(ir.copy(model = model, options = ir.options.copy(reasoning = reasoning)))
        return (if (f == WireFormat.GEMINI) JsonObject(out + ("model" to kotlinx.serialization.json.JsonPrimitive(model))) else out).toString()
    }

    private fun response(from: WireFormat, to: WireFormat): (String) -> String =
        { b -> ProtocolConverter.convertResponse(parse(b), from, to).value.toString() }

    /** 请求：某协议 body → OpenAI hub body。openai=恒等。 */
    fun requestToHub(protocol: String): (String) -> String =
        formatOf(protocol)?.takeIf { it != WireFormat.OPENAI_CHAT }?.let { request(it, WireFormat.OPENAI_CHAT) } ?: identityStr()

    /** 请求：OpenAI hub body → 某协议 body。 */
    fun requestFromHub(protocol: String): (String) -> String =
        formatOf(protocol)?.takeIf { it != WireFormat.OPENAI_CHAT }?.let { request(WireFormat.OPENAI_CHAT, it) } ?: identityStr()

    /** 响应：某协议 body → OpenAI hub body（上游原生 → hub）。 */
    fun responseToHub(protocol: String): (String) -> String =
        formatOf(protocol)?.takeIf { it != WireFormat.OPENAI_CHAT }?.let { response(it, WireFormat.OPENAI_CHAT) } ?: identityStr()

    /** 响应：OpenAI hub body → 某协议 body（hub → 入站）。 */
    fun responseFromHub(protocol: String): (String) -> String =
        formatOf(protocol)?.takeIf { it != WireFormat.OPENAI_CHAT }?.let { response(WireFormat.OPENAI_CHAT, it) } ?: identityStr()

    /** 流式：某协议 SSE → OpenAI chunk 流。null=恒等透传。 */
    fun streamToHub(protocol: String, model: String): StreamStage? =
        formatOf(protocol)?.takeIf { it != WireFormat.OPENAI_CHAT }?.let { stage(it, WireFormat.OPENAI_CHAT, model) }

    /** 流式：OpenAI chunk 流 → 某协议 SSE。null=恒等透传。 */
    fun streamFromHub(protocol: String, model: String): StreamStage? =
        formatOf(protocol)?.takeIf { it != WireFormat.OPENAI_CHAT }?.let { stage(WireFormat.OPENAI_CHAT, it, model) }

    /**
     * RelayEngine 只在确认上游完整结束（见到终止标记）后才调用 finish；截断时不调用、按 PARTIAL 收尾。
     * 所以这里的 finish 一律按正常结束输出终止事件。
     */
    private fun stage(from: WireFormat, to: WireFormat, model: String): StreamStage =
        ProtocolConverter.streamConverter(from, to, defaultModel = model).let { c -> StreamStage({ c.feed(it) }, { c.finish(truncated = false) }) }
}

/** 单个流式转换阶段（feed/finish）。 */
class StreamStage(val feed: (String) -> String, val finish: () -> String)
