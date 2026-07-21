package logic.protocol.codec

import kotlinx.serialization.json.*

/** OpenAI ChatCompletions 请求 → Anthropic Messages 请求（反向直译）。 */
object OpenAiToAnthropicRequest {

    private val json = Json { }
    private const val DEFAULT_MAX_TOKENS = 4096

    fun convert(openaiBody: String): String {
        val src = json.parseToJsonElement(openaiBody).jsonObject
        val systemParts = mutableListOf<String>()
        val messages = buildAnthropicMessages(src["messages"]!!.jsonArray, systemParts)

        val out = buildJsonObject {
            src["model"]?.let { put("model", it) }
            put("max_tokens", src["max_tokens"] ?: JsonPrimitive(DEFAULT_MAX_TOKENS))
            src["temperature"]?.let { put("temperature", it) }
            src["top_p"]?.let { put("top_p", it) }
            src["stream"]?.let { put("stream", it) }
            (src["stop"] as? JsonArray)?.let { put("stop_sequences", it) }
            (src["stop"] as? JsonPrimitive)?.let { put("stop_sequences", buildJsonArray { add(it) }) }
            if (systemParts.isNotEmpty()) put("system", systemParts.joinToString("\n"))
            put("messages", messages)
            (src["tools"] as? JsonArray)?.let { put("tools", convertTools(it)) }
            src["tool_choice"]?.let { put("tool_choice", convertToolChoice(it)) }
        }
        return out.toString()
    }

    private fun buildAnthropicMessages(oaMessages: JsonArray, systemParts: MutableList<String>): JsonArray = buildJsonArray {
        for (m in oaMessages) {
            val msg = m.jsonObject
            when (msg["role"]?.jsonPrimitive?.content) {
                "system" -> msg["content"]?.jsonPrimitive?.contentOrNull?.let { systemParts += it }
                "tool" -> add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", msg["tool_call_id"] ?: JsonPrimitive(""))
                            put("content", msg["content"] ?: JsonPrimitive(""))
                        })
                    })
                })
                "assistant" -> {
                    val toolCalls = msg["tool_calls"] as? JsonArray
                    if (toolCalls != null && toolCalls.isNotEmpty()) {
                        add(buildJsonObject {
                            put("role", "assistant")
                            put("content", buildJsonArray {
                                msg["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                                    add(buildJsonObject { put("type", "text"); put("text", it) })
                                }
                                for (tc in toolCalls) {
                                    val call = tc.jsonObject
                                    val fn = call["function"]?.jsonObject
                                    val args = fn?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "{}"
                                    add(buildJsonObject {
                                        put("type", "tool_use")
                                        put("id", call["id"] ?: JsonPrimitive(""))
                                        put("name", fn?.get("name") ?: JsonPrimitive(""))
                                        put("input", runCatching { json.parseToJsonElement(args) }.getOrElse { buildJsonObject {} })
                                    })
                                }
                            })
                        })
                    } else {
                        add(buildJsonObject { put("role", "assistant"); put("content", msg["content"] ?: JsonPrimitive("")) })
                    }
                }
                else -> add(buildJsonObject { put("role", "user"); put("content", msg["content"] ?: JsonPrimitive("")) })
            }
        }
    }

    private fun convertTools(tools: JsonArray): JsonArray = buildJsonArray {
        for (t in tools) {
            val fn = t.jsonObject["function"]?.jsonObject ?: continue
            add(buildJsonObject {
                put("name", fn["name"] ?: JsonPrimitive(""))
                fn["description"]?.let { put("description", it) }
                put("input_schema", fn["parameters"] ?: buildJsonObject { put("type", "object") })
            })
        }
    }

    private fun convertToolChoice(choice: JsonElement): JsonElement = when {
        choice is JsonPrimitive -> when (choice.contentOrNull) {
            "required" -> buildJsonObject { put("type", "any") }
            "none" -> buildJsonObject { put("type", "auto") }
            else -> buildJsonObject { put("type", "auto") }
        }
        choice is JsonObject -> buildJsonObject {
            put("type", "tool")
            put("name", choice["function"]?.jsonObject?.get("name") ?: JsonPrimitive(""))
        }
        else -> buildJsonObject { put("type", "auto") }
    }
}
