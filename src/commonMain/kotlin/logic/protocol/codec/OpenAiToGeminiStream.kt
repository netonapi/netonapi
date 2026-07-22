package logic.protocol.codec

import kotlinx.serialization.json.*

/** OpenAI chunk 流 → Gemini streamGenerateContent SSE。 */
class OpenAiToGeminiStream(private val model: String) {
    private val json = Json { }
    private val buffer = StringBuilder()
    private var finished = false
    private var finishReason = "STOP"
    private var promptTokens = 0
    private var completionTokens = 0

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

    private fun process(chunk: JsonObject, out: StringBuilder) {
        (chunk["usage"] as? JsonObject)?.let { u ->
            u["prompt_tokens"]?.jsonPrimitive?.intOrNull?.let { promptTokens = it }
            u["completion_tokens"]?.jsonPrimitive?.intOrNull?.let { completionTokens = it }
        }
        val choice = (chunk["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return
        choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = OpenAiToGeminiResponse.mapFinish(it) }
        val text = choice["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        if (!text.isNullOrEmpty()) {
            emit(out, buildJsonObject {
                put("candidates", buildJsonArray {
                    add(buildJsonObject { put("content", buildJsonObject {
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) }); put("role", "model")
                    }); put("index", 0) })
                })
            })
        }
    }

    fun finish(): String {
        if (finished) return ""; finished = true
        val out = StringBuilder()
        emit(out, buildJsonObject {
            put("candidates", buildJsonArray {
                add(buildJsonObject {
                    put("content", buildJsonObject { put("parts", buildJsonArray {}); put("role", "model") })
                    put("finishReason", finishReason); put("index", 0)
                })
            })
            put("usageMetadata", buildJsonObject {
                put("promptTokenCount", promptTokens); put("candidatesTokenCount", completionTokens)
                put("totalTokenCount", promptTokens + completionTokens)
            })
        })
        return out.toString()
    }

    private fun emit(out: StringBuilder, obj: JsonObject) {
        out.append("data: ").append(obj.toString()).append("\n\n")
    }
}
