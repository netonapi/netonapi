package logic.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import neton.ai.protocol.convert.ProtocolConverter
import neton.ai.protocol.diag.ConversionRejectedException
import neton.ai.protocol.diag.LossPolicy
import neton.ai.protocol.ir.WireFormat

/**
 * `/v1/responses` 打非 OpenAI 上游（转换在 neton-ai：Responses → hub → 上游）。
 *
 * 只桥接无状态请求：`previous_response_id`、`conversation`、`background` 依赖 OpenAI 服务端保存的状态，
 * 别家上游给不出来；OpenAI 托管工具（web_search 等）也执行不了。这些请求在转换前就跳过非 OpenAI 渠道，
 * 由 OpenAI 渠道服务，不静默降级。
 */
object ResponsesBridge {
    const val PROTOCOL = "openai_responses"
    private val json = Json { ignoreUnknownKeys = true }

    /** 能否桥接到 [upstream]；不能时返回原因。 */
    fun rejection(rawBody: String): String? {
        val body = runCatching { json.parseToJsonElement(rawBody) as? JsonObject }.getOrNull() ?: return null
        for (k in listOf("previous_response_id", "conversation")) {
            if (body[k] != null && body[k] !is JsonNull) return "Responses '$k' needs an OpenAI upstream"
        }
        if (body["background"]?.jsonPrimitive?.booleanOrNull == true) return "Responses background mode needs an OpenAI upstream"
        return try {
            ProtocolConverter.convertRequest(body, WireFormat.OPENAI_RESPONSES, WireFormat.OPENAI_CHAT, LossPolicy.Safe())
            null
        } catch (e: ConversionRejectedException) {
            "Responses request cannot be served by this upstream: " + e.violations.joinToString { it.code }
        }
    }
}
