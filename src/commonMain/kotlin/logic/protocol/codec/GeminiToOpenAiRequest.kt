package logic.protocol.codec

import kotlinx.serialization.json.*

/**
 * Gemini generateContent 请求 → OpenAI ChatCompletions（model 从注入的 "model" 字段取）。
 * inlineData 图片 → image_url（data URL）；fileData 的 http 地址 → image_url；
 * functionCall 的 id 用 name（Gemini 没有调用 id，functionResponse 也按 name 回配）；
 * thinkingConfig.thinkingBudget → reasoning_effort；responseMimeType=application/json → response_format。
 */
object GeminiToOpenAiRequest {
    private val json = Json { }

    fun convert(geminiBody: String): String {
        val src = json.parseToJsonElement(geminiBody).jsonObject
        val gc = src["generationConfig"]?.jsonObject
        val out = buildJsonObject {
            src["model"]?.let { put("model", it) }
            src["stream"]?.let {
                put("stream", it)
                if (it.jsonPrimitive.booleanOrNull == true) put("stream_options", buildJsonObject { put("include_usage", true) })
            }
            gc?.get("maxOutputTokens")?.let { put("max_tokens", it) }
            gc?.get("temperature")?.let { put("temperature", it) }
            gc?.get("topP")?.let { put("top_p", it) }
            (gc?.get("stopSequences") as? JsonArray)?.let { put("stop", it) }
            (gc?.get("thinkingConfig") as? JsonObject)?.get("thinkingBudget")?.jsonPrimitive?.intOrNull?.let { b ->
                CodecSupport.reasoningEffort(b)?.let { put("reasoning_effort", it) }
            }
            if (gc?.get("responseMimeType")?.jsonPrimitive?.contentOrNull == "application/json") {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
            put("messages", buildMessages(src))
            (src["tools"] as? JsonArray)?.let { tools ->
                val converted = convertTools(tools)
                if (converted.isNotEmpty()) put("tools", converted)
            }
            ((src["toolConfig"] as? JsonObject)?.get("functionCallingConfig") as? JsonObject)?.let { fc ->
                when (fc["mode"]?.jsonPrimitive?.contentOrNull) {
                    "NONE" -> put("tool_choice", "none")
                    "ANY" -> {
                        val names = fc["allowedFunctionNames"] as? JsonArray
                        if (names != null && names.size == 1) put("tool_choice", buildJsonObject {
                            put("type", "function"); put("function", buildJsonObject { put("name", names[0]) })
                        }) else put("tool_choice", "required")
                    }
                    else -> {}
                }
            }
        }
        return out.toString()
    }

    private fun buildMessages(src: JsonObject): JsonArray = buildJsonArray {
        (src["systemInstruction"]?.jsonObject?.get("parts") as? JsonArray)?.let { parts ->
            val text = parts.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.joinToString("\n")
            if (text.isNotEmpty()) add(buildJsonObject { put("role", "system"); put("content", text) })
        }
        for (c in src["contents"]!!.jsonArray) {
            val content = c.jsonObject
            val gRole = content["role"]?.jsonPrimitive?.contentOrNull ?: "user"
            val role = if (gRole == "model") "assistant" else "user"
            val parts = content["parts"] as? JsonArray ?: continue
            val texts = mutableListOf<String>()
            val images = mutableListOf<JsonObject>()
            val toolCalls = mutableListOf<JsonObject>()
            val toolResults = mutableListOf<JsonObject>()
            for (p in parts) {
                val part = p.jsonObject
                when {
                    part["thought"]?.jsonPrimitive?.booleanOrNull == true -> {}  // 历史里的思考块对 OpenAI 无意义
                    part.containsKey("text") -> texts += part["text"]!!.jsonPrimitive.contentOrNull ?: ""
                    part.containsKey("inlineData") -> {
                        val d = part["inlineData"]!!.jsonObject
                        images += buildJsonObject {
                            put("kind", "base64"); put("media", d["mimeType"] ?: JsonPrimitive("image/png")); put("data", d["data"] ?: JsonPrimitive(""))
                        }
                    }
                    part.containsKey("fileData") -> {
                        val d = part["fileData"]!!.jsonObject
                        d["fileUri"]?.jsonPrimitive?.contentOrNull?.let { images += buildJsonObject { put("kind", "url"); put("url", it) } }
                    }
                    part.containsKey("functionCall") -> {
                        val fc = part["functionCall"]!!.jsonObject
                        toolCalls += buildJsonObject {
                            put("id", fc["id"] ?: fc["name"] ?: JsonPrimitive(""))
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", fc["name"] ?: JsonPrimitive(""))
                                put("arguments", JsonPrimitive((fc["args"] ?: buildJsonObject {}).toString()))
                            })
                        }
                    }
                    part.containsKey("functionResponse") -> {
                        val fr = part["functionResponse"]!!.jsonObject
                        toolResults += buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", fr["id"] ?: fr["name"] ?: JsonPrimitive(""))
                            put("content", (fr["response"] ?: buildJsonObject {}).toString())
                        }
                    }
                }
            }
            addAll(toolResults)
            if (images.isNotEmpty()) {
                add(buildJsonObject {
                    put("role", role)
                    put("content", buildJsonArray {
                        if (texts.isNotEmpty()) add(buildJsonObject { put("type", "text"); put("text", texts.joinToString("\n")) })
                        images.forEach { add(CodecSupport.openAiImage(it)) }
                    })
                })
            } else if (toolCalls.isNotEmpty()) {
                add(buildJsonObject {
                    put("role", role)
                    put("content", if (texts.isNotEmpty()) JsonPrimitive(texts.joinToString("\n")) else JsonNull)
                    put("tool_calls", JsonArray(toolCalls))
                })
            } else if (texts.isNotEmpty() || toolResults.isEmpty()) {
                add(buildJsonObject { put("role", role); put("content", texts.joinToString("\n")) })
            }
        }
    }

    private fun convertTools(tools: JsonArray): JsonArray = buildJsonArray {
        for (t in tools) {
            (t.jsonObject["functionDeclarations"] as? JsonArray)?.forEach { fd ->
                val fn = fd.jsonObject
                add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", fn["name"] ?: JsonPrimitive(""))
                        fn["description"]?.let { put("description", it) }
                        put("parameters", fn["parameters"] ?: fn["parametersJsonSchema"] ?: buildJsonObject { put("type", "object") })
                    })
                })
            }
        }
    }
}
