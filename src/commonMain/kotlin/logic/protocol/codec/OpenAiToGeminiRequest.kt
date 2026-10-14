package logic.protocol.codec

import kotlinx.serialization.json.*

/**
 * OpenAI ChatCompletions 请求 → Gemini generateContent。
 * image_url → inlineData（data URL）/ fileData（http）；tool 消息 → functionResponse（按 tool_call_id 回配 name，
 * Gemini 只认函数名，所以 id 在 OpenAI→Gemini 方向记成 name→id 映射）；reasoning_effort → thinkingConfig。
 */
object OpenAiToGeminiRequest {
    private val json = Json { }

    fun convert(openaiBody: String): String = convert(openaiBody, logic.protocol.GeminiRequestOptions(), logic.protocol.SignatureDiagnostics())

    /**
     * [options] 决定缺签名时是否补占位值；[diagnostics] 收集签名诊断（spec/ai.md §4.5）。
     * 真实签名（hub tool call 的 `extra_content.google.thought_signature`）一律原样写回，不覆盖。
     */
    fun convert(openaiBody: String, options: logic.protocol.GeminiRequestOptions, diagnostics: logic.protocol.SignatureDiagnostics): String {
        val src = json.parseToJsonElement(openaiBody).jsonObject
        val systemTexts = mutableListOf<String>()
        val contents = buildContents(src["messages"]!!.jsonArray, systemTexts, options, diagnostics)
        val out = buildJsonObject {
            put("contents", contents)
            if (systemTexts.isNotEmpty()) put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", systemTexts.joinToString("\n")) }) })
            })
            put("generationConfig", buildJsonObject {
                (src["max_completion_tokens"] ?: src["max_tokens"])?.let { put("maxOutputTokens", it) }
                src["temperature"]?.let { put("temperature", it) }
                src["top_p"]?.let { put("topP", it) }
                (src["stop"] as? JsonArray)?.let { put("stopSequences", it) }
                (src["stop"] as? JsonPrimitive)?.let { put("stopSequences", buildJsonArray { add(it) }) }
                CodecSupport.thinkingBudget(src["reasoning_effort"]?.jsonPrimitive?.contentOrNull)?.let {
                    put("thinkingConfig", buildJsonObject { put("thinkingBudget", it); put("includeThoughts", true) })
                }
                (src["response_format"] as? JsonObject)?.let { rf ->
                    when (rf["type"]?.jsonPrimitive?.contentOrNull) {
                        "json_object" -> put("responseMimeType", "application/json")
                        "json_schema" -> {
                            put("responseMimeType", "application/json")
                            (rf["json_schema"] as? JsonObject)?.get("schema")?.let { put("responseSchema", it) }
                        }
                        else -> {}
                    }
                }
            })
            (src["tools"] as? JsonArray)?.let { put("tools", convertTools(it)) }
            src["tool_choice"]?.let { tc ->
                val mode = when {
                    tc is JsonPrimitive && tc.contentOrNull == "none" -> "NONE"
                    tc is JsonPrimitive && tc.contentOrNull == "required" -> "ANY"
                    tc is JsonObject -> "ANY"
                    else -> "AUTO"
                }
                put("toolConfig", buildJsonObject {
                    put("functionCallingConfig", buildJsonObject {
                        put("mode", mode)
                        if (tc is JsonObject) (tc["function"] as? JsonObject)?.get("name")?.let { put("allowedFunctionNames", buildJsonArray { add(it) }) }
                    })
                })
            }
        }
        return out.toString()
    }

    private fun buildContents(
        msgs: JsonArray, systemTexts: MutableList<String>,
        options: logic.protocol.GeminiRequestOptions, diagnostics: logic.protocol.SignatureDiagnostics,
    ): JsonArray {
        val callNames = mutableMapOf<String, String>()  // tool_call_id → 函数名
        val list = mutableListOf<JsonObject>()
        // 当前轮 = 最后一条用户消息之后（tool 结果不算用户消息）。Google 只校验当前轮。
        val currentTurnStart = msgs.indexOfLast { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "user" }
        for ((msgIndex, m) in msgs.withIndex()) {
            val msg = m.jsonObject
            when (msg["role"]?.jsonPrimitive?.contentOrNull) {
                "system", "developer" -> CodecSupport.textOf(msg["content"]).takeIf { it.isNotEmpty() }?.let { systemTexts += it }
                "tool" -> {
                    val id = msg["tool_call_id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val raw = CodecSupport.textOf(msg["content"])
                    val response = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                        ?: buildJsonObject { put("content", raw) }
                    list += buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("functionResponse", buildJsonObject {
                                put("name", callNames[id] ?: id)
                                put("response", response)
                            }) })
                        })
                    }
                }
                "assistant" -> {
                    val parts = CodecSupport.openAiParts(msg["content"])
                    val out = mutableListOf<JsonObject>()
                    parts.texts.joinToString("\n").takeIf { it.isNotEmpty() }?.let { out += buildJsonObject { put("text", it) } }
                    var firstCallIndex = -1
                    (msg["tool_calls"] as? JsonArray)?.forEach { tc ->
                        val call = tc.jsonObject
                        val fn = call["function"]?.jsonObject
                        val name = fn?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                        call["id"]?.jsonPrimitive?.contentOrNull?.let { callNames[it] = name }
                        val signature = logic.protocol.GeminiThoughtSignatures.fromToolCall(call)
                        if (firstCallIndex < 0) firstCallIndex = out.size
                        out += buildJsonObject {
                            put("functionCall", buildJsonObject {
                                put("name", name)
                                put("args", runCatching { json.parseToJsonElement(fn?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "{}") }.getOrElse { buildJsonObject {} })
                            })
                            // 真实签名原样写回（Google 只在每步第一个 functionCall 上给签名，并行调用的其余没有）
                            signature?.let { put("thoughtSignature", it) }
                        }
                    }
                    // 每一步的第一个 functionCall 没有真实签名：按选项补占位值，否则记为缺失（只统计当前轮）
                    if (firstCallIndex >= 0 && !out[firstCallIndex].containsKey("thoughtSignature")) {
                        if (options.signatureBypass) {
                            out[firstCallIndex] = JsonObject(out[firstCallIndex] + ("thoughtSignature" to JsonPrimitive(logic.protocol.GeminiThoughtSignatures.BYPASS_VALUE)))
                            diagnostics.bypassed++
                        } else if (msgIndex > currentTurnStart) {
                            diagnostics.missingInCurrentTurn++
                        }
                    }
                    if (out.isNotEmpty()) list += buildJsonObject { put("role", "model"); put("parts", JsonArray(out)) }
                }
                else -> {
                    val parts = CodecSupport.openAiParts(msg["content"])
                    list += buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray {
                            parts.texts.joinToString("\n").let { if (it.isNotEmpty() || parts.images.isEmpty()) add(buildJsonObject { put("text", it) }) }
                            parts.images.forEach { img ->
                                if (img["kind"]?.jsonPrimitive?.content == "url") add(buildJsonObject { put("fileData", buildJsonObject { put("fileUri", img["url"]!!) }) })
                                else add(buildJsonObject { put("inlineData", buildJsonObject { put("mimeType", img["media"]!!); put("data", img["data"]!!) }) })
                            }
                        })
                    }
                }
            }
        }
        // 相邻同角色合并（连续 functionResponse 必须在同一个 user content 里）
        return buildJsonArray {
            var cur: JsonObject? = null
            for (m in list) {
                if (cur != null && cur["role"] == m["role"]) {
                    cur = buildJsonObject { put("role", m["role"]!!); put("parts", JsonArray(cur!!["parts"]!!.jsonArray + m["parts"]!!.jsonArray)) }
                } else { cur?.let { add(it) }; cur = m }
            }
            cur?.let { add(it) }
        }
    }

    private fun convertTools(tools: JsonArray): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("functionDeclarations", buildJsonArray {
                for (t in tools) {
                    val fn = t.jsonObject["function"]?.jsonObject ?: continue
                    add(buildJsonObject {
                        put("name", fn["name"] ?: JsonPrimitive(""))
                        fn["description"]?.let { put("description", it) }
                        put("parameters", fn["parameters"] ?: buildJsonObject { put("type", "object") })
                    })
                }
            })
        })
    }
}
