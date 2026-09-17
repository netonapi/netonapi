package logic.protocol.codec

import kotlinx.serialization.json.*

/** OpenAI ChatCompletions 响应 → Gemini generateContent。reasoning_content → thought 部分。 */
object OpenAiToGeminiResponse {
    private val json = Json { }

    fun convert(openaiBody: String): String {
        val src = json.parseToJsonElement(openaiBody).jsonObject
        val choice = (src["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
        val msg = choice?.get("message")?.jsonObject
        val parts = buildJsonArray {
            msg?.get("reasoning_content")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                add(buildJsonObject { put("text", it); put("thought", true) })
            }
            CodecSupport.openAiParts(msg?.get("content")).texts.joinToString("\n").takeIf { it.isNotEmpty() }?.let {
                add(buildJsonObject { put("text", it) })
            }
            (msg?.get("tool_calls") as? JsonArray)?.forEach { tc ->
                val fn = tc.jsonObject["function"]?.jsonObject
                add(buildJsonObject { put("functionCall", buildJsonObject {
                    tc.jsonObject["id"]?.let { put("id", it) }
                    put("name", fn?.get("name") ?: JsonPrimitive(""))
                    put("args", runCatching { json.parseToJsonElement(fn?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "{}") }.getOrElse { buildJsonObject {} })
                }) })
            }
        }
        val out = buildJsonObject {
            put("candidates", buildJsonArray {
                add(buildJsonObject {
                    put("content", buildJsonObject { put("parts", parts); put("role", "model") })
                    put("finishReason", mapFinish(choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull))
                    put("index", 0)
                })
            })
            put("usageMetadata", mapUsage(src["usage"]?.jsonObject))
            src["model"]?.let { put("modelVersion", it) }
            src["id"]?.let { put("responseId", it) }
        }
        return out.toString()
    }

    fun mapFinish(reason: String?): String = when (reason) {
        "stop" -> "STOP"; "length" -> "MAX_TOKENS"
        "content_filter" -> "SAFETY"; "tool_calls" -> "STOP"
        else -> "STOP"
    }

    fun mapUsage(u: JsonObject?): JsonObject = buildJsonObject {
        val p = u?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val c = u?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val cached = (u?.get("prompt_tokens_details") as? JsonObject)?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val thoughts = (u?.get("completion_tokens_details") as? JsonObject)?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull ?: 0
        put("promptTokenCount", p); put("candidatesTokenCount", (c - thoughts).coerceAtLeast(0)); put("totalTokenCount", p + c)
        if (cached > 0) put("cachedContentTokenCount", cached)
        if (thoughts > 0) put("thoughtsTokenCount", thoughts)
    }
}
