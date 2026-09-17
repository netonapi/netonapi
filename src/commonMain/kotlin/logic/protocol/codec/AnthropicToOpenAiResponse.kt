package logic.protocol.codec

import kotlinx.serialization.json.*

/**
 * Anthropic Messages 响应 → OpenAI ChatCompletions 响应（非流式）。
 * thinking 块放进 reasoning_content（DeepSeek 起的事实标准，Cherry Studio / NextChat 等客户端都认）；
 * 用量：OpenAI 的 prompt_tokens 是全量输入，Anthropic 的 input_tokens 不含缓存命中，要把三项加起来。
 */
object AnthropicToOpenAiResponse {
    private val json = Json { }

    fun convert(anthropicBody: String): String {
        val src = json.parseToJsonElement(anthropicBody).jsonObject
        val blocks = src["content"] as? JsonArray ?: JsonArray(emptyList())
        val texts = mutableListOf<String>()
        val thoughts = mutableListOf<String>()
        val toolCalls = mutableListOf<JsonObject>()
        for (b in blocks) {
            val block = b.jsonObject
            when (block["type"]?.jsonPrimitive?.content) {
                "text" -> block["text"]?.jsonPrimitive?.contentOrNull?.let { texts += it }
                "thinking" -> block["thinking"]?.jsonPrimitive?.contentOrNull?.let { thoughts += it }
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
                        if (thoughts.isNotEmpty()) put("reasoning_content", thoughts.joinToString("\n"))
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
        "refusal" -> "content_filter"
        else -> "stop"
    }

    fun mapUsage(usage: JsonObject?): JsonObject = buildJsonObject {
        val input = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val cacheRead = usage?.get("cache_read_input_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val cacheWrite = usage?.get("cache_creation_input_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val output = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val prompt = input + cacheRead + cacheWrite
        put("prompt_tokens", prompt)
        put("completion_tokens", output)
        put("total_tokens", prompt + output)
        if (cacheRead > 0) put("prompt_tokens_details", buildJsonObject { put("cached_tokens", cacheRead) })
    }
}
