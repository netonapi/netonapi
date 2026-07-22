package logic.protocol.codec

import kotlinx.serialization.json.*

/** Gemini generateContent 响应 → OpenAI ChatCompletions（非流式）。 */
object GeminiToOpenAiResponse {
    private val json = Json { }

    fun convert(geminiBody: String): String {
        val src = json.parseToJsonElement(geminiBody).jsonObject
        val cand = (src["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
        val parts = cand?.get("content")?.jsonObject?.get("parts") as? JsonArray
        val texts = mutableListOf<String>()
        val toolCalls = mutableListOf<JsonObject>()
        parts?.forEach { p ->
            val part = p.jsonObject
            when {
                part.containsKey("text") -> texts += part["text"]!!.jsonPrimitive.contentOrNull ?: ""
                part.containsKey("functionCall") -> {
                    val fc = part["functionCall"]!!.jsonObject
                    toolCalls += buildJsonObject {
                        put("id", fc["name"] ?: JsonPrimitive("")); put("type", "function")
                        put("function", buildJsonObject {
                            put("name", fc["name"] ?: JsonPrimitive(""))
                            put("arguments", JsonPrimitive((fc["args"] ?: buildJsonObject {}).toString()))
                        })
                    }
                }
            }
        }
        val out = buildJsonObject {
            put("id", "chatcmpl_gemini")
            put("object", "chat.completion")
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", if (texts.isNotEmpty()) JsonPrimitive(texts.joinToString("\n")) else JsonNull)
                        if (toolCalls.isNotEmpty()) put("tool_calls", JsonArray(toolCalls))
                    })
                    put("finish_reason", mapFinish(cand?.get("finishReason")?.jsonPrimitive?.contentOrNull))
                })
            })
            put("usage", mapUsage(src["usageMetadata"]?.jsonObject))
        }
        return out.toString()
    }

    fun mapFinish(reason: String?): String = when (reason) {
        "STOP" -> "stop"; "MAX_TOKENS" -> "length"
        "SAFETY", "RECITATION" -> "content_filter"
        else -> "stop"
    }

    private fun mapUsage(u: JsonObject?): JsonObject = buildJsonObject {
        val p = u?.get("promptTokenCount")?.jsonPrimitive?.intOrNull ?: 0
        val c = u?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull ?: 0
        put("prompt_tokens", p); put("completion_tokens", c); put("total_tokens", p + c)
    }
}
