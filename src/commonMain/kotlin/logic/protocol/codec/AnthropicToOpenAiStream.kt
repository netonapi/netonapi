package logic.protocol.codec

import kotlinx.serialization.json.*

/**
 * Anthropic Messages 事件流 → OpenAI ChatCompletions chunk 流（状态机）。
 * thinking_delta → delta.reasoning_content；用量按 [AnthropicToOpenAiResponse.mapUsage] 的口径合并。
 */
class AnthropicToOpenAiStream(private val model: String) {
    private val json = Json { }
    private val framer = logic.protocol.SseEventFramer()
    private var roleSent = false
    private var finished = false
    private var finishReason: String = "stop"
    private var usage: JsonObject = buildJsonObject {}
    private val id = "chatcmpl_stream"

    fun feed(chunk: String): String {
        val out = StringBuilder()
        for (raw in framer.frame(chunk)) {
            val data = raw.trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
            process(obj, out)
        }
        return out.toString()
    }

    private fun process(ev: JsonObject, out: StringBuilder) {
        when (ev["type"]?.jsonPrimitive?.content) {
            "message_start" -> {
                ((ev["message"] as? JsonObject)?.get("usage") as? JsonObject)?.let { usage = mergeUsage(usage, it) }
            }
            "content_block_start" -> {
                val index = ev["index"]?.jsonPrimitive?.intOrNull ?: 0
                val cb = ev["content_block"]?.jsonObject
                if (cb?.get("type")?.jsonPrimitive?.content == "tool_use") {
                    emitChunk(out, buildJsonObject {
                        if (!roleSent) { put("role", "assistant"); roleSent = true }
                        put("tool_calls", buildJsonArray {
                            add(buildJsonObject {
                                put("index", index)
                                put("id", cb["id"] ?: JsonPrimitive(""))
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", cb["name"] ?: JsonPrimitive("")); put("arguments", "")
                                })
                            })
                        })
                    })
                }
            }
            "content_block_delta" -> {
                val index = ev["index"]?.jsonPrimitive?.intOrNull ?: 0
                val delta = ev["delta"]?.jsonObject ?: return
                when (delta["type"]?.jsonPrimitive?.content) {
                    "text_delta" -> emitChunk(out, buildJsonObject {
                        if (!roleSent) { put("role", "assistant"); roleSent = true }
                        put("content", delta["text"] ?: JsonPrimitive(""))
                    })
                    "thinking_delta" -> emitChunk(out, buildJsonObject {
                        if (!roleSent) { put("role", "assistant"); roleSent = true }
                        put("reasoning_content", delta["thinking"] ?: JsonPrimitive(""))
                    })
                    "input_json_delta" -> emitChunk(out, buildJsonObject {
                        put("tool_calls", buildJsonArray {
                            add(buildJsonObject {
                                put("index", index)
                                put("function", buildJsonObject { put("arguments", delta["partial_json"] ?: JsonPrimitive("")) })
                            })
                        })
                    })
                }
            }
            "message_delta" -> {
                (ev["delta"] as? JsonObject)?.get("stop_reason")?.jsonPrimitive?.contentOrNull?.let {
                    finishReason = AnthropicToOpenAiResponse.mapFinishReason(it)
                }
                (ev["usage"] as? JsonObject)?.let { usage = mergeUsage(usage, it) }
            }
            "message_stop" -> {}
        }
    }

    /** message_start 给 input 三项，message_delta 给 output（有时也重复 input）；后来的覆盖先来的。 */
    private fun mergeUsage(base: JsonObject, incoming: JsonObject): JsonObject = buildJsonObject {
        base.forEach { (k, v) -> put(k, v) }
        incoming.forEach { (k, v) -> if (v !is JsonNull) put(k, v) }
    }

    fun finish(): String {
        if (finished) return ""
        finished = true
        val out = StringBuilder()
        out.append("data: ")
        out.append(buildJsonObject {
            put("id", id); put("object", "chat.completion.chunk"); put("model", model)
            put("choices", buildJsonArray {
                add(buildJsonObject { put("index", 0); put("delta", buildJsonObject {}); put("finish_reason", finishReason) })
            })
            put("usage", AnthropicToOpenAiResponse.mapUsage(usage))
        }.toString())
        out.append("\n\n")
        out.append("data: [DONE]\n\n")
        return out.toString()
    }

    private fun emitChunk(out: StringBuilder, delta: JsonObject) {
        out.append("data: ")
        out.append(buildJsonObject {
            put("id", id); put("object", "chat.completion.chunk"); put("model", model)
            put("choices", buildJsonArray {
                add(buildJsonObject { put("index", 0); put("delta", delta); put("finish_reason", JsonNull) })
            })
        }.toString())
        out.append("\n\n")
    }
}
