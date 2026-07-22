package logic.protocol.codec

import kotlinx.serialization.json.*

/** Gemini generateContent 请求 → OpenAI ChatCompletions（model 从注入的 "model" 字段取）。 */
object GeminiToOpenAiRequest {
    private val json = Json { }

    fun convert(geminiBody: String): String {
        val src = json.parseToJsonElement(geminiBody).jsonObject
        val gc = src["generationConfig"]?.jsonObject
        val out = buildJsonObject {
            src["model"]?.let { put("model", it) }
            src["stream"]?.let { put("stream", it) }
            gc?.get("maxOutputTokens")?.let { put("max_tokens", it) }
            gc?.get("temperature")?.let { put("temperature", it) }
            gc?.get("topP")?.let { put("top_p", it) }
            (gc?.get("stopSequences") as? JsonArray)?.let { put("stop", it) }
            put("messages", buildMessages(src))
            (src["tools"] as? JsonArray)?.let { put("tools", convertTools(it)) }
        }
        return out.toString()
    }

    private fun buildMessages(src: JsonObject): JsonArray = buildJsonArray {
        (src["systemInstruction"]?.jsonObject?.get("parts") as? JsonArray)?.let { parts ->
            val text = parts.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.joinToString("\n")
            if (text.isNotEmpty()) add(buildJsonObject { put("role", "system"); put("content", text) })
        }
        for (c in src["contents"]!!.jsonArray) {
            val content = c.jsonObject
            val gRole = content["role"]?.jsonPrimitive?.contentOrNull ?: "user"
            val role = if (gRole == "model") "assistant" else "user"
            val parts = content["parts"]!!.jsonArray
            val texts = mutableListOf<String>()
            val toolCalls = mutableListOf<JsonObject>()
            var functionResponse: JsonObject? = null
            for (p in parts) {
                val part = p.jsonObject
                when {
                    part.containsKey("text") -> texts += part["text"]!!.jsonPrimitive.contentOrNull ?: ""
                    part.containsKey("functionCall") -> {
                        val fc = part["functionCall"]!!.jsonObject
                        toolCalls += buildJsonObject {
                            put("id", fc["name"] ?: JsonPrimitive(""))
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", fc["name"] ?: JsonPrimitive(""))
                                put("arguments", JsonPrimitive((fc["args"] ?: buildJsonObject {}).toString()))
                            })
                        }
                    }
                    part.containsKey("functionResponse") -> functionResponse = part["functionResponse"]!!.jsonObject
                }
            }
            if (functionResponse != null) {
                add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", functionResponse["name"] ?: JsonPrimitive(""))
                    put("content", (functionResponse["response"] ?: buildJsonObject {}).toString())
                })
            } else if (toolCalls.isNotEmpty()) {
                add(buildJsonObject {
                    put("role", role)
                    put("content", if (texts.isNotEmpty()) JsonPrimitive(texts.joinToString("\n")) else JsonNull)
                    put("tool_calls", JsonArray(toolCalls))
                })
            } else {
                add(buildJsonObject { put("role", role); put("content", texts.joinToString("\n")) })
            }
        }
    }

    private fun convertTools(tools: JsonArray): JsonArray = buildJsonArray {
        for (t in tools) {
            (t.jsonObject["functionDeclarations"] as? JsonArray)?.forEach { fd ->
                val fn = fd.jsonObject
                add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", fn["name"] ?: JsonPrimitive(""))
                        fn["description"]?.let { put("description", it) }
                        put("parameters", fn["parameters"] ?: buildJsonObject { put("type", "object") })
                    })
                })
            }
        }
    }
}
