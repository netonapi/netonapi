package logic.protocol.codec

import kotlinx.serialization.json.*

/**
 * OpenAI SSE chunk 流 → Anthropic Messages 事件序列（状态机）。
 * 输出：message_start → [thinking 块] → [text 块] → [tool_use 块...] → message_delta → message_stop。
 * reasoning_content 增量进 thinking 块（收尾补一个空 signature_delta，SDK 累加器需要它）；
 * 块之间严格先关后开，Anthropic 客户端按 index 累加，乱序会拼坏。
 */
class OpenAiToAnthropicStream(private val model: String) {
    private val json = Json { }
    private val framer = logic.protocol.SseEventFramer()
    private var started = false
    private var openKind: String? = null   // "thinking" | "text" | null
    private var openIndex = -1
    private var nextIndex = 0
    private var finished = false
    private val toolBlockIndex = mutableMapOf<Int, Int>()
    private var stopReason: String = "end_turn"
    private var usage: JsonObject? = null

    fun feed(chunk: String): String {
        val out = StringBuilder()
        for (raw in framer.frame(chunk)) {
            val data = raw.trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
            processChunk(obj, out)
        }
        return out.toString()
    }

    private fun processChunk(chunk: JsonObject, out: StringBuilder) {
        if (!started) {
            started = true
            emit(out, "message_start", buildJsonObject {
                put("type", "message_start")
                put("message", buildJsonObject {
                    put("id", chunk["id"]?.takeIf { it is JsonPrimitive } ?: JsonPrimitive("msg_stream"))
                    put("type", "message"); put("role", "assistant")
                    put("model", model)
                    put("content", buildJsonArray {})
                    put("stop_reason", JsonNull)
                    put("usage", buildJsonObject { put("input_tokens", 0); put("output_tokens", 0) })
                })
            })
        }
        (chunk["usage"] as? JsonObject)?.let { usage = it }
        val choice = (chunk["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return
        val delta = choice["delta"]?.jsonObject
        choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let {
            stopReason = OpenAiToAnthropicResponse.mapStopReason(it)
        }
        val thought = delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
        if (!thought.isNullOrEmpty()) {
            ensureOpen(out, "thinking")
            emit(out, "content_block_delta", buildJsonObject {
                put("type", "content_block_delta"); put("index", openIndex)
                put("delta", buildJsonObject { put("type", "thinking_delta"); put("thinking", thought) })
            })
        }
        val text = CodecSupport.openAiParts(delta?.get("content")).texts.joinToString("")
        if (text.isNotEmpty()) {
            ensureOpen(out, "text")
            emit(out, "content_block_delta", buildJsonObject {
                put("type", "content_block_delta"); put("index", openIndex)
                put("delta", buildJsonObject { put("type", "text_delta"); put("text", text) })
            })
        }
        (delta?.get("tool_calls") as? JsonArray)?.forEach { tc ->
            val call = tc.jsonObject
            val oaIndex = call["index"]?.jsonPrimitive?.intOrNull ?: 0
            val fn = call["function"]?.jsonObject
            if (oaIndex !in toolBlockIndex) {
                closeOpen(out)
                val bi = nextIndex++
                toolBlockIndex[oaIndex] = bi
                emit(out, "content_block_start", buildJsonObject {
                    put("type", "content_block_start"); put("index", bi)
                    put("content_block", buildJsonObject {
                        put("type", "tool_use")
                        put("id", call["id"] ?: JsonPrimitive("call_$bi"))
                        put("name", fn?.get("name") ?: JsonPrimitive(""))
                        put("input", buildJsonObject {})
                    })
                })
            }
            val argsFragment = fn?.get("arguments")?.jsonPrimitive?.contentOrNull
            if (!argsFragment.isNullOrEmpty()) {
                emit(out, "content_block_delta", buildJsonObject {
                    put("type", "content_block_delta"); put("index", toolBlockIndex[oaIndex]!!)
                    put("delta", buildJsonObject { put("type", "input_json_delta"); put("partial_json", argsFragment) })
                })
            }
        }
    }

    private fun ensureOpen(out: StringBuilder, kind: String) {
        if (openKind == kind) return
        closeOpen(out)
        openIndex = nextIndex++
        openKind = kind
        emit(out, "content_block_start", buildJsonObject {
            put("type", "content_block_start"); put("index", openIndex)
            put("content_block", if (kind == "thinking") buildJsonObject { put("type", "thinking"); put("thinking", "") }
                else buildJsonObject { put("type", "text"); put("text", "") })
        })
    }

    private fun closeOpen(out: StringBuilder) {
        val kind = openKind ?: return
        if (kind == "thinking") {
            emit(out, "content_block_delta", buildJsonObject {
                put("type", "content_block_delta"); put("index", openIndex)
                put("delta", buildJsonObject { put("type", "signature_delta"); put("signature", "") })
            })
        }
        closeBlock(out, openIndex)
        openKind = null
    }

    fun finish(): String {
        if (finished) return ""
        finished = true
        val out = StringBuilder()
        if (!started) return ""  // 无内容流：不产出（上游首字节前已失败走重试路径）
        closeOpen(out)
        toolBlockIndex.values.sorted().forEach { closeBlock(out, it) }
        toolBlockIndex.clear()
        emit(out, "message_delta", buildJsonObject {
            put("type", "message_delta")
            put("delta", buildJsonObject { put("stop_reason", stopReason); put("stop_sequence", JsonNull) })
            put("usage", OpenAiToAnthropicResponse.mapUsage(usage))
        })
        emit(out, "message_stop", buildJsonObject { put("type", "message_stop") })
        return out.toString()
    }

    private fun closeBlock(out: StringBuilder, index: Int) {
        emit(out, "content_block_stop", buildJsonObject { put("type", "content_block_stop"); put("index", index) })
    }

    private fun emit(out: StringBuilder, event: String, data: JsonObject) {
        out.append("event: ").append(event).append('\n')
            .append("data: ").append(data.toString()).append("\n\n")
    }
}
