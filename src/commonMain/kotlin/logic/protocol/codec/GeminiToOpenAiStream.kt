package logic.protocol.codec

import kotlinx.serialization.json.*

/** Gemini streamGenerateContent SSE → OpenAI chunk 流。thought → reasoning_content；functionCall → tool_calls 增量。 */
class GeminiToOpenAiStream(private val model: String) {
    private val json = Json { }
    private val framer = logic.protocol.SseEventFramer()
    private var roleSent = false
    private var finished = false
    private var finishReason = "stop"
    private var toolIndex = 0
    private var usage: JsonObject? = null
    private val id = "chatcmpl_gemini_stream"

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
        (ev["usageMetadata"] as? JsonObject)?.let { usage = it }
        val cand = (ev["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return
        cand["finishReason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = GeminiToOpenAiResponse.mapFinish(it) }
        val parts = cand["content"]?.jsonObject?.get("parts") as? JsonArray ?: return
        for (p in parts) {
            val part = p.jsonObject
            when {
                part["thought"]?.jsonPrimitive?.booleanOrNull == true -> part["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                    emitChunk(out, buildJsonObject { role(); put("reasoning_content", it) })
                }
                part.containsKey("text") -> part["text"]!!.jsonPrimitive.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                    emitChunk(out, buildJsonObject { role(); put("content", it) })
                }
                part.containsKey("functionCall") -> {
                    // Gemini 一次给完整 functionCall，映射成「开始 + 全量参数」一个 chunk
                    val call = GeminiToOpenAiResponse.functionCallToToolCall(part["functionCall"]!!.jsonObject, toolIndex, logic.protocol.GeminiThoughtSignatures.fromGeminiPart(part))
                    emitChunk(out, buildJsonObject {
                        role()
                        put("tool_calls", buildJsonArray { add(buildJsonObject { put("index", toolIndex); call.forEach { (k, v) -> put(k, v) } }) })
                    })
                    toolIndex++
                    finishReason = "tool_calls"
                }
            }
        }
    }

    private fun JsonObjectBuilder.role() { if (!roleSent) { put("role", "assistant"); roleSent = true } }

    fun finish(): String {
        if (finished) return ""; finished = true
        val out = StringBuilder()
        out.append("data: ").append(buildJsonObject {
            put("id", id); put("object", "chat.completion.chunk"); put("model", model)
            put("choices", buildJsonArray { add(buildJsonObject { put("index", 0); put("delta", buildJsonObject {}); put("finish_reason", finishReason) }) })
            put("usage", GeminiToOpenAiResponse.mapUsage(usage))
        }.toString()).append("\n\n").append("data: [DONE]\n\n")
        return out.toString()
    }

    private fun emitChunk(out: StringBuilder, delta: JsonObject) {
        out.append("data: ").append(buildJsonObject {
            put("id", id); put("object", "chat.completion.chunk"); put("model", model)
            put("choices", buildJsonArray { add(buildJsonObject { put("index", 0); put("delta", delta); put("finish_reason", JsonNull) }) })
        }.toString()).append("\n\n")
    }
}
