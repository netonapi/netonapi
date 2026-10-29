package logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 请求没带输出上限、预扣用了模型的默认上限（defaultMaxOutputTokens）时，把这个上限写进转发的请求：
 * 否则上游可以输出得比预扣的多，结算时按实际扣费就会超出预留（OVERAGE），余额可能被扣成负数。
 *
 * 写在**入站协议**的字段上，之后的模型映射与协议转换照常处理；OpenAI 写 `max_tokens`，发往 OpenAI 官方推理模型时
 * 由 neton-ai 的 ProviderProfile 换成 `max_completion_tokens`。客户端自己给了任一上限字段时不动。
 */
object OutputCap {

    private val json = Json { ignoreUnknownKeys = true }

    /** [protocol]：入站协议（openai / responses / anthropic / gemini）；其他协议与非 JSON 请求体原样返回。 */
    fun inject(body: String, protocol: String, cap: Int): String {
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return body
        if (CAP_FIELDS.any { it in o } || (o["generationConfig"] as? JsonObject)?.containsKey("maxOutputTokens") == true) return body
        val patched = when (protocol) {
            "openai", "anthropic" -> JsonObject(o + ("max_tokens" to JsonPrimitive(cap)))
            "responses" -> JsonObject(o + ("max_output_tokens" to JsonPrimitive(cap)))
            "gemini" -> {
                val gc = (o["generationConfig"] as? JsonObject).orEmpty()
                JsonObject(o + ("generationConfig" to JsonObject(gc + ("maxOutputTokens" to JsonPrimitive(cap)))))
            }
            else -> return body
        }
        return patched.toString()
    }

    private val CAP_FIELDS = setOf("max_tokens", "max_completion_tokens", "max_output_tokens")
}
