package logic.protocol.codec

import kotlinx.serialization.json.*

/**
 * OpenAI chunk 流 → Gemini streamGenerateContent SSE。
 * reasoning_content → thought 部分；tool_calls 的参数是分片到达的，Gemini 要整只 functionCall，
 * 所以按 index 攒到 finish（或 finish_reason 到来）再一次性放出。
 */
class OpenAiToGeminiStream(private val model: String) {
    private val json = Json { }
    private val framer = logic.protocol.SseEventFramer()
    private var finished = false
    private var finishReason = "STOP"
    private var usage: JsonObject? = null
    private val pendingCalls = linkedMapOf<Int, Triple<String, String, StringBuilder>>()  // index → (id, name, args)

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

    private fun process(chunk: JsonObject, out: StringBuilder) {
        (chunk["usage"] as? JsonObject)?.let { usage = it }
        val choice = (chunk["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return
        choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = OpenAiToGeminiResponse.mapFinish(it) }
        val delta = choice["delta"]?.jsonObject
        delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
            emitParts(out, buildJsonArray { add(buildJsonObject { put("text", it); put("thought", true) }) })
        }
        CodecSupport.openAiParts(delta?.get("content")).texts.joinToString("").takeIf { it.isNotEmpty() }?.let {
            emitParts(out, buildJsonArray { add(buildJsonObject { put("text", it) }) })
        }
        (delta?.get("tool_calls") as? JsonArray)?.forEach { tc ->
            val call = tc.jsonObject
            val idx = call["index"]?.jsonPrimitive?.intOrNull ?: 0
            val fn = call["function"]?.jsonObject
            val cur = pendingCalls.getOrPut(idx) { Triple(call["id"]?.jsonPrimitive?.contentOrNull ?: "", fn?.get("name")?.jsonPrimitive?.contentOrNull ?: "", StringBuilder()) }
            val merged = Triple(
                cur.first.ifEmpty { call["id"]?.jsonPrimitive?.contentOrNull ?: "" },
                cur.second.ifEmpty { fn?.get("name")?.jsonPrimitive?.contentOrNull ?: "" },
                cur.third.apply { fn?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { append(it) } },
            )
            pendingCalls[idx] = merged
        }
    }

    private fun flushCalls(out: StringBuilder) {
        if (pendingCalls.isEmpty()) return
        emitParts(out, buildJsonArray {
            for ((_, c) in pendingCalls) add(buildJsonObject { put("functionCall", buildJsonObject {
                if (c.first.isNotEmpty()) put("id", c.first)
                put("name", c.second)
                put("args", runCatching { json.parseToJsonElement(c.third.toString().ifEmpty { "{}" }) }.getOrElse { buildJsonObject {} })
            }) })
        })
        pendingCalls.clear()
    }

    fun finish(): String {
        if (finished) return ""; finished = true
        val out = StringBuilder()
        flushCalls(out)
        emit(out, buildJsonObject {
            put("candidates", buildJsonArray {
                add(buildJsonObject {
                    put("content", buildJsonObject { put("parts", buildJsonArray {}); put("role", "model") })
                    put("finishReason", finishReason); put("index", 0)
                })
            })
            put("usageMetadata", OpenAiToGeminiResponse.mapUsage(usage))
        })
        return out.toString()
    }

    private fun emitParts(out: StringBuilder, parts: JsonArray) {
        emit(out, buildJsonObject {
            put("candidates", buildJsonArray {
                add(buildJsonObject { put("content", buildJsonObject { put("parts", parts); put("role", "model") }); put("index", 0) })
            })
        })
    }

    private fun emit(out: StringBuilder, obj: JsonObject) {
        out.append("data: ").append(obj.toString()).append("\n\n")
    }
}
