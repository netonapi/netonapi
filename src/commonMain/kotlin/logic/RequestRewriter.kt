package logic

import kotlinx.serialization.json.*
import model.Channel

data class Rewritten(val body: String, val requestModel: String, val upstreamModel: String, val stream: Boolean)

/**
 * 同协议最小侵入改写（spec 决策 A）：只动 model / 合并 param_override / 流式补 include_usage，
 * 其余字段经 JsonObject 原样保留（未知字段零丢失）。
 */
object RequestRewriter {

    private val json = Json { ignoreUnknownKeys = false }

    fun rewrite(rawBody: String, channel: Channel, injectStreamOptions: Boolean = true): Rewritten {
        val obj = json.parseToJsonElement(rawBody).jsonObject
        val requestModel = obj["model"]?.jsonPrimitive?.contentOrNull ?: ""
        val stream = obj["stream"]?.jsonPrimitive?.booleanOrNull ?: false

        val mapping: JsonObject? = channel.modelMapping
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val upstreamModel = mapping?.get(requestModel)?.jsonPrimitive?.contentOrNull ?: requestModel

        val override: JsonObject? = channel.paramOverride
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

        val merged = buildJsonObject {
            obj.forEach { (k, v) -> put(k, v) }
            put("model", JsonPrimitive(upstreamModel))
            override?.forEach { (k, v) -> put(k, v) }
            if (injectStreamOptions && stream && !obj.containsKey("stream_options")) {
                put("stream_options", buildJsonObject { put("include_usage", JsonPrimitive(true)) })
            }
        }
        return Rewritten(body = merged.toString(), requestModel = requestModel, upstreamModel = upstreamModel, stream = stream)
    }
}
