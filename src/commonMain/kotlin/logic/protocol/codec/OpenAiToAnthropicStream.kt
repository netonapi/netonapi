package logic.protocol.codec

import kotlinx.serialization.json.*

/**
 * OpenAI SSE chunk 流 → Anthropic Messages 事件序列（状态机）。
 * feed(chunk) 增量喂入 OpenAI SSE 文本，返回该批产生的 Anthropic 事件文本；结束调 finish() 补收尾。
 * 输出序列：message_start → content_block_start(text) → content_block_delta* →
 *   [tool_use 块...] → content_block_stop → message_delta → message_stop。
 */
class OpenAiToAnthropicStream(private val model: String) {

    private val json = Json { }
    private val framer = logic.protocol.SseEventFramer()

    private var started = false
    private var textBlockOpen = false
    private var textBlockIndex = -1
    private var nextIndex = 0
    private var finished = false

    // tool_use 块：OpenAI tool_calls[].index → 已分配的 Anthropic block index
    private val toolBlockIndex = mutableMapOf<Int, Int>()

    private var stopReason: String = "end_turn"
    private var inputTokens = 0
    private var outputTokens = 0

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
                    put("id", "msg_stream")
                    put("type", "message"); put("role", "assistant")
                    put("model", model)
                    put("content", buildJsonArray {})
                    put("stop_reason", JsonNull)
                    put("usage", buildJsonObject { put("input_tokens", 0); put("output_tokens", 0) })
                })
            })
        }
        (chunk["usage"] as? JsonObject)?.let { u ->
            u["prompt_tokens"]?.jsonPrimitive?.intOrNull?.let { inputTokens = it }
            u["completion_tokens"]?.jsonPrimitive?.intOrNull?.let { outputTokens = it }
        }
        val choice = (chunk["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return
        val delta = choice["delta"]?.jsonObject
        choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let {
            stopReason = OpenAiToAnthropicResponse.mapStopReason(it)
        }

        val text = delta?.get("content")?.jsonPrimitive?.contentOrNull
        if (!text.isNullOrEmpty()) {
            if (!textBlockOpen) {
                textBlockIndex = nextIndex++
                textBlockOpen = true
                emit(out, "content_block_start", buildJsonObject {
                    put("type", "content_block_start"); put("index", textBlockIndex)
                    put("content_block", buildJsonObject { put("type", "text"); put("text", "") })
                })
            }
            emit(out, "content_block_delta", buildJsonObject {
                put("type", "content_block_delta"); put("index", textBlockIndex)
                put("delta", buildJsonObject { put("type", "text_delta"); put("text", text) })
            })
        }

        (delta?.get("tool_calls") as? JsonArray)?.forEach { tc ->
            val call = tc.jsonObject
            val oaIndex = call["index"]?.jsonPrimitive?.intOrNull ?: 0
            val fn = call["function"]?.jsonObject
            if (oaIndex !in toolBlockIndex) {
                // 关掉正在开的 text 块
                if (textBlockOpen) { closeBlock(out, textBlockIndex); textBlockOpen = false }
                val bi = nextIndex++
                toolBlockIndex[oaIndex] = bi
                emit(out, "content_block_start", buildJsonObject {
                    put("type", "content_block_start"); put("index", bi)
                    put("content_block", buildJsonObject {
                        put("type", "tool_use")
                        put("id", call["id"] ?: JsonPrimitive(""))
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

    fun finish(): String {
        if (finished) return ""
        finished = true
        val out = StringBuilder()
        if (!started) return ""  // 无内容流：不产出（上游首字节前已失败走重试路径）
        if (textBlockOpen) { closeBlock(out, textBlockIndex); textBlockOpen = false }
        toolBlockIndex.values.forEach { closeBlock(out, it) }
        toolBlockIndex.clear()
        emit(out, "message_delta", buildJsonObject {
            put("type", "message_delta")
            put("delta", buildJsonObject { put("stop_reason", stopReason); put("stop_sequence", JsonNull) })
            put("usage", buildJsonObject { put("input_tokens", inputTokens); put("output_tokens", outputTokens) })
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
