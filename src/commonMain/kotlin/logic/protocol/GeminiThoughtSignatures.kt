package logic.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import neton.security.digest.Sha256

/**
 * Gemini thought signature 的协议约定（neton-docs spec/ai.md §4.5）。
 *
 * 依据 Google 官方文档 generate-content/thought-signatures：Gemini 3 及之后的型号在当前轮里，
 * 任何一步的**第一个** functionCall 缺签名即 400；文本签名不是必需的；OpenAI 兼容格式把签名放在
 * 每个 tool call 的 `extra_content.google.thought_signature`。gateway 的 hub（OpenAI ChatCompletions）
 * 沿用这个官方字段在内部携带签名，所以 hub 本身不丢签名。
 */
object GeminiThoughtSignatures {
    /** 官方占位值之一。只用于缺少真实签名的 functionCall（官方强烈不鼓励，见 spec §4.5.2）。 */
    const val BYPASS_VALUE = neton.ai.protocol.codec.GeminiCodec.SIGNATURE_BYPASS_VALUE

    /** hub tool call 上的签名；没有返回 null。 */
    fun fromToolCall(toolCall: JsonObject): String? =
        ((toolCall["extra_content"] as? JsonObject)?.get("google") as? JsonObject)
            ?.get("thought_signature")?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotEmpty() }

    /** 给 hub tool call 加上签名（覆盖同名字段，保留 extra_content 里的其他厂商字段）。 */
    fun withSignature(toolCall: JsonObject, signature: String): JsonObject {
        val extra = (toolCall["extra_content"] as? JsonObject) ?: JsonObject(emptyMap())
        val google = (extra["google"] as? JsonObject) ?: JsonObject(emptyMap())
        val newGoogle = JsonObject(google + ("thought_signature" to JsonPrimitive(signature)))
        val newExtra = JsonObject(extra + ("google" to newGoogle))
        return JsonObject(toolCall + ("extra_content" to newExtra))
    }

    /** 去掉 hub tool call 上的 Google 签名（其他厂商字段保留；extra_content 空了就整个去掉）。 */
    fun withoutSignature(toolCall: JsonObject): JsonObject {
        val extra = toolCall["extra_content"] as? JsonObject ?: return toolCall
        val google = extra["google"] as? JsonObject
        val newGoogle = google?.let { JsonObject(it - "thought_signature") }
        val newExtra = if (newGoogle == null || newGoogle.isEmpty()) JsonObject(extra - "google")
            else JsonObject(extra + ("google" to newGoogle))
        return if (newExtra.isEmpty()) JsonObject(toolCall - "extra_content")
            else JsonObject(toolCall + ("extra_content" to newExtra))
    }

    /** Gemini part 上的签名。 */
    fun fromGeminiPart(part: JsonObject): String? =
        part["thoughtSignature"]?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotEmpty() }

    /**
     * 调用内容摘要：函数名 + 规范化参数（键排序、无多余空白）。
     * 状态引用取回签名时比对它：客户端改了参数，就不能再套用原来那次推理的签名（spec §4.5.1）。
     */
    fun callDigest(name: String, argumentsJson: String): String {
        val canonical = runCatching { canonicalize(kotlinx.serialization.json.Json.parseToJsonElement(argumentsJson)) }
            .getOrElse { argumentsJson.trim() }
        return Sha256.hex("$name\n$canonical".encodeToByteArray())
    }

    private fun canonicalize(e: JsonElement): String = when (e) {
        is JsonObject -> e.keys.sorted().joinToString(",", "{", "}") { k -> JsonPrimitive(k).toString() + ":" + canonicalize(e[k]!!) }
        is JsonArray -> e.joinToString(",", "[", "]") { canonicalize(it) }
        is JsonNull -> "null"
        is JsonPrimitive -> e.toString()
    }

    /** 诊断代码（spec §4.5.1 / §4.5.2）。 */
    const val DIAG_MISSING = neton.ai.protocol.diag.DiagnosticCodes.GEMINI_THOUGHT_SIGNATURE_MISSING
    const val DIAG_BYPASSED = neton.ai.protocol.diag.DiagnosticCodes.GEMINI_THOUGHT_SIGNATURE_BYPASSED
}

/** OpenAI→Gemini 请求转换的签名选项。 */
data class GeminiRequestOptions(
    /** 缺签名时写入官方占位值（有损兼容；调用方须已确认该渠道与模型通过验证）。 */
    val signatureBypass: Boolean = false,
    /** 目标型号是否校验签名；null = 按 hub body 的 model 推断（gemini-3 及之后）。 */
    val requireSignatures: Boolean? = null,
)

/** hub（OpenAI Chat）→ Gemini 请求，带签名选项与诊断计数（转换本身在 neton-ai 协议层）。 */
object GeminiRequestConverter {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun convert(hubBody: String, options: GeminiRequestOptions, diagnostics: SignatureDiagnostics): String {
        val r = neton.ai.protocol.convert.ProtocolConverter.convertRequest(
            json.parseToJsonElement(hubBody) as JsonObject,
            neton.ai.protocol.ir.WireFormat.OPENAI_CHAT, neton.ai.protocol.ir.WireFormat.GEMINI,
            gemini = neton.ai.protocol.codec.GeminiEncodeOptions(options.signatureBypass, options.requireSignatures),
        )
        diagnostics.missingInCurrentTurn += r.diagnostics.count { it.code == GeminiThoughtSignatures.DIAG_MISSING }
        diagnostics.bypassed += r.diagnostics.count { it.code == GeminiThoughtSignatures.DIAG_BYPASSED }
        return r.value.toString()
    }
}

/** 一次转换中产生的签名诊断（gateway 写日志与请求记录用）。 */
data class SignatureDiagnostics(
    /** 当前轮里第一个 functionCall 没有真实签名、也没有补占位值的步数。 */
    var missingInCurrentTurn: Int = 0,
    /** 补了占位值的步数。 */
    var bypassed: Int = 0,
)
