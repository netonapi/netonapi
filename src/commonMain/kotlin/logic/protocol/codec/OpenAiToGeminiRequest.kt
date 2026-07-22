package logic.protocol.codec

import kotlinx.serialization.json.*

/** OpenAI ChatCompletions 请求 → Gemini generateContent（反向）。 */
object OpenAiToGeminiRequest {
    private val json = Json { }

    fun convert(openaiBody: String): String {
        val src = json.parseToJsonElement(openaiBody).jsonObject
        val systemTexts = mutableListOf<String>()
        val contents = buildContents(src["messages"]!!.jsonArray, systemTexts)
        val out = buildJsonObject {
            put("contents", contents)
            if (systemTexts.isNotEmpty()) put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", systemTexts.joinToString("\n")) }) })
            })
            put("generationConfig", buildJsonObject {
                src["max_tokens"]?.let { put("maxOutputTokens", it) }
                src["temperature"]?.let { put("temperature", it) }
                src["top_p"]?.let { put("topP", it) }
                (src["stop"] as? JsonArray)?.let { put("stopSequences", it) }
            })
            (src["tools"] as? JsonArray)?.let { put("tools", convertTools(it)) }
        }
        return out.toString()
    }

    private fun buildContents(msgs: JsonArray, systemTexts: MutableList<String>): JsonArray = buildJsonArray {
        for (m in msgs) {
            val msg = m.jsonObject
            when (msg["role"]?.jsonPrimitive?.content) {
                "system" -> msg["content"]?.jsonPrimitive?.contentOrNull?.let { systemTexts += it }
                "tool" -> add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("functionResponse", buildJsonObject {
                            put("name", msg["tool_call_id"] ?: JsonPrimitive(""))
                            put("response", buildJsonObject { put("content", msg["content"] ?: JsonPrimitive("")) })
                        }) })
                    })
                })
                "assistant" -> add(buildJsonObject {
                    put("role", "model")
                    put("parts", buildJsonArray {
                        msg["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                            add(buildJsonObject { put("text", it) })
                        }
                        (msg["tool_calls"] as? JsonArray)?.forEach { tc ->
                            val fn = tc.jsonObject["function"]?.jsonObject
                            add(buildJsonObject { put("functionCall", buildJsonObject {
                                put("name", fn?.get("name") ?: JsonPrimitive(""))
                                put("args", runCatching { json.parseToJsonElement(fn?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "{}") }.getOrElse { buildJsonObject {} })
                            }) })
                        }
                    })
                })
                else -> add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", msg["content"]?.jsonPrimitive?.contentOrNull ?: "") }) })
                })
            }
        }
    }

    private fun convertTools(tools: JsonArray): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("functionDeclarations", buildJsonArray {
                for (t in tools) {
                    val fn = t.jsonObject["function"]?.jsonObject ?: continue
                    add(buildJsonObject {
                        put("name", fn["name"] ?: JsonPrimitive(""))
                        fn["description"]?.let { put("description", it) }
                        put("parameters", fn["parameters"] ?: buildJsonObject { put("type", "object") })
                    })
                }
            })
        })
    }
}
