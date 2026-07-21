package logic.protocol.codec

import kotlinx.serialization.json.*

/** OpenAI ChatCompletions 响应 → Anthropic Messages 响应（非流式）。 */
object OpenAiToAnthropicResponse {

    private val json = Json { }

    fun convert(openaiBody: String): String {
        val src = json.parseToJsonElement(openaiBody).jsonObject
        val choice = (src["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
        val message = choice?.get("message")?.jsonObject
        val finishReason = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull

        val out = buildJsonObject {
            put("id", src["id"] ?: JsonPrimitive("msg_0"))
            put("type", "message")
            put("role", "assistant")
            src["model"]?.let { put("model", it) }
            put("content", buildContent(message))
            put("stop_reason", mapStopReason(finishReason))
            put("stop_sequence", JsonNull)
            put("usage", mapUsage(src["usage"]?.jsonObject))
        }
        return out.toString()
    }

    private fun buildContent(message: JsonObject?): JsonArray = buildJsonArray {
        val text = message?.get("content")?.jsonPrimitive?.contentOrNull
        if (!text.isNullOrEmpty()) {
            add(buildJsonObject { put("type", "text"); put("text", text) })
        }
        (message?.get("tool_calls") as? JsonArray)?.forEach { tc ->
            val call = tc.jsonObject
            val fn = call["function"]?.jsonObject
            val argsStr = fn?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "{}"
            add(buildJsonObject {
                put("type", "tool_use")
                put("id", call["id"] ?: JsonPrimitive(""))
                put("name", fn?.get("name") ?: JsonPrimitive(""))
                put("input", runCatching { json.parseToJsonElement(argsStr) }.getOrElse { buildJsonObject {} })
            })
        }
    }

    fun mapStopReason(finishReason: String?): String = when (finishReason) {
        "stop" -> "end_turn"
        "length" -> "max_tokens"
        "tool_calls", "function_call" -> "tool_use"
        "content_filter" -> "end_turn"
        else -> "end_turn"
    }

    private fun mapUsage(usage: JsonObject?): JsonObject = buildJsonObject {
        put("input_tokens", usage?.get("prompt_tokens") ?: JsonPrimitive(0))
        put("output_tokens", usage?.get("completion_tokens") ?: JsonPrimitive(0))
    }
}
