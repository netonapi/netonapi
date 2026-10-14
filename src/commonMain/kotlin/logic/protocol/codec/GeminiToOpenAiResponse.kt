package logic.protocol.codec

import kotlinx.serialization.json.*

/** Gemini generateContent 响应 → OpenAI ChatCompletions（非流式）。thought 部分 → reasoning_content。 */
object GeminiToOpenAiResponse {
    private val json = Json { }

    fun convert(geminiBody: String): String {
        val src = json.parseToJsonElement(geminiBody).jsonObject
        val cand = (src["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
        val parts = cand?.get("content")?.jsonObject?.get("parts") as? JsonArray
        val texts = mutableListOf<String>()
        val thoughts = mutableListOf<String>()
        val toolCalls = mutableListOf<JsonObject>()
        parts?.forEachIndexed { i, p ->
            val part = p.jsonObject
            when {
                part["thought"]?.jsonPrimitive?.booleanOrNull == true -> thoughts += part["text"]?.jsonPrimitive?.contentOrNull ?: ""
                part.containsKey("text") -> texts += part["text"]!!.jsonPrimitive.contentOrNull ?: ""
                part.containsKey("functionCall") -> toolCalls += functionCallToToolCall(part["functionCall"]!!.jsonObject, i, logic.protocol.GeminiThoughtSignatures.fromGeminiPart(part))
            }
        }
        val out = buildJsonObject {
            put("id", src["responseId"] ?: JsonPrimitive("chatcmpl_gemini"))
            put("object", "chat.completion")
            src["modelVersion"]?.let { put("model", it) }
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", if (texts.isNotEmpty()) JsonPrimitive(texts.joinToString("\n")) else JsonNull)
                        if (thoughts.isNotEmpty()) put("reasoning_content", thoughts.joinToString("\n"))
                        if (toolCalls.isNotEmpty()) put("tool_calls", JsonArray(toolCalls))
                    })
                    put("finish_reason", if (toolCalls.isNotEmpty()) "tool_calls" else mapFinish(cand?.get("finishReason")?.jsonPrimitive?.contentOrNull))
                })
            })
            put("usage", mapUsage(src["usageMetadata"]?.jsonObject))
        }
        return out.toString()
    }

    /**
     * Gemini functionCall → hub tool call。[signature] 是该 functionCall 部分的 thought signature，
     * 按 Google 官方的 OpenAI 兼容约定放进 `extra_content.google.thought_signature`（spec/ai.md §4.5）。
     */
    fun functionCallToToolCall(fc: JsonObject, index: Int, signature: String? = null): JsonObject {
        val call = buildJsonObject {
            put("id", fc["id"] ?: JsonPrimitive("call_${fc["name"]?.jsonPrimitive?.contentOrNull ?: ""}_$index"))
            put("type", "function")
            put("function", buildJsonObject {
                put("name", fc["name"] ?: JsonPrimitive(""))
                put("arguments", JsonPrimitive((fc["args"] ?: buildJsonObject {}).toString()))
            })
        }
        return if (signature != null) logic.protocol.GeminiThoughtSignatures.withSignature(call, signature) else call
    }

    fun mapFinish(reason: String?): String = when (reason) {
        "STOP" -> "stop"; "MAX_TOKENS" -> "length"
        "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> "content_filter"
        else -> "stop"
    }

    fun mapUsage(u: JsonObject?): JsonObject = buildJsonObject {
        val p = u?.get("promptTokenCount")?.jsonPrimitive?.intOrNull ?: 0
        val c = u?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull ?: 0
        val thoughts = u?.get("thoughtsTokenCount")?.jsonPrimitive?.intOrNull ?: 0
        val cached = u?.get("cachedContentTokenCount")?.jsonPrimitive?.intOrNull ?: 0
        put("prompt_tokens", p); put("completion_tokens", c + thoughts); put("total_tokens", p + c + thoughts)
        if (cached > 0) put("prompt_tokens_details", buildJsonObject { put("cached_tokens", cached) })
        if (thoughts > 0) put("completion_tokens_details", buildJsonObject { put("reasoning_tokens", thoughts) })
    }
}
