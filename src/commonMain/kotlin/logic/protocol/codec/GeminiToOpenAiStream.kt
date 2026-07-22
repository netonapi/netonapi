package logic.protocol.codec

import kotlinx.serialization.json.*

/** Gemini streamGenerateContent SSE → OpenAI chunk 流。 */
class GeminiToOpenAiStream(private val model: String) {
    private val json = Json { }
    private val buffer = StringBuilder()
    private var roleSent = false
    private var finished = false
    private var finishReason = "stop"
    private var promptTokens = 0
    private var completionTokens = 0
    private val id = "chatcmpl_gemini_stream"

    fun feed(chunk: String): String {
        buffer.append(chunk)
        val out = StringBuilder()
        while (true) {
            val idx = buffer.indexOf("\n\n")
            if (idx < 0) break
            val event = buffer.substring(0, idx); buffer.deleteRange(0, idx + 2)
            for (line in event.lineSequence()) {
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty() || data == line || data == "[DONE]") continue
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                process(obj, out)
            }
        }
        return out.toString()
    }

    private fun process(ev: JsonObject, out: StringBuilder) {
        (ev["usageMetadata"] as? JsonObject)?.let { u ->
            u["promptTokenCount"]?.jsonPrimitive?.intOrNull?.let { promptTokens = it }
            u["candidatesTokenCount"]?.jsonPrimitive?.intOrNull?.let { completionTokens = it }
        }
        val cand = (ev["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return
        cand["finishReason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = GeminiToOpenAiResponse.mapFinish(it) }
        val parts = cand["content"]?.jsonObject?.get("parts") as? JsonArray ?: return
        val text = parts.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.joinToString("")
        if (text.isNotEmpty()) {
            emitChunk(out, buildJsonObject {
                if (!roleSent) { put("role", "assistant"); roleSent = true }
                put("content", text)
            })
        }
    }

    fun finish(): String {
        if (finished) return ""; finished = true
        val out = StringBuilder()
        out.append("data: ").append(buildJsonObject {
            put("id", id); put("object", "chat.completion.chunk"); put("model", model)
            put("choices", buildJsonArray { add(buildJsonObject { put("index", 0); put("delta", buildJsonObject {}); put("finish_reason", finishReason) }) })
            put("usage", buildJsonObject { put("prompt_tokens", promptTokens); put("completion_tokens", completionTokens); put("total_tokens", promptTokens + completionTokens) })
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
