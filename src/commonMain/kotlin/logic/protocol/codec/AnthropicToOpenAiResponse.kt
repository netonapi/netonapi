package logic.protocol.codec

import kotlinx.serialization.json.*

/** Anthropic Messages 响应 → OpenAI ChatCompletions 响应（非流式，反向）。 */
object AnthropicToOpenAiResponse {

    private val json = Json { }

    fun convert(anthropicBody: String): String {
        val src = json.parseToJsonElement(anthropicBody).jsonObject
        val blocks = src["content"] as? JsonArray ?: JsonArray(emptyList())
        val texts = mutableListOf<String>()
        val toolCalls = mutableListOf<JsonObject>()
        for (b in blocks) {
            val block = b.jsonObject
            when (block["type"]?.jsonPrimitive?.content) {
                "text" -> block["text"]?.jsonPrimitive?.contentOrNull?.let { texts += it }
                "tool_use" -> toolCalls += buildJsonObject {
                    put("id", block["id"] ?: JsonPrimitive(""))
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", block["name"] ?: JsonPrimitive(""))
                        put("arguments", JsonPrimitive((block["input"] ?: buildJsonObject {}).toString()))
                    })
                }
            }
        }
        val out = buildJsonObject {
            put("id", src["id"] ?: JsonPrimitive("chatcmpl_0"))
            put("object", "chat.completion")
            src["model"]?.let { put("model", it) }
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", if (texts.isNotEmpty()) JsonPrimitive(texts.joinToString("\n")) else JsonNull)
                        if (toolCalls.isNotEmpty()) put("tool_calls", JsonArray(toolCalls))
                    })
                    put("finish_reason", mapFinishReason(src["stop_reason"]?.jsonPrimitive?.contentOrNull))
                })
            })
            put("usage", mapUsage(src["usage"]?.jsonObject))
        }
        return out.toString()
    }

    fun mapFinishReason(stopReason: String?): String = when (stopReason) {
        "end_turn", "stop_sequence" -> "stop"
        "max_tokens" -> "length"
        "tool_use" -> "tool_calls"
        else -> "stop"
    }

    private fun mapUsage(usage: JsonObject?): JsonObject = buildJsonObject {
        val input = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val output = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0
        put("prompt_tokens", input)
        put("completion_tokens", output)
        put("total_tokens", input + output)
    }
}
