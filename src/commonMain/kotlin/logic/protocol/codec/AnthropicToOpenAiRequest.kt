package logic.protocol.codec

import kotlinx.serialization.json.*

/** Anthropic Messages 请求 → OpenAI ChatCompletions 请求（跨协议直译，不经 IR）。 */
object AnthropicToOpenAiRequest {

    private val json = Json { }

    fun convert(anthropicBody: String): String {
        val src = json.parseToJsonElement(anthropicBody).jsonObject
        val out = buildJsonObject {
            src["model"]?.let { put("model", it) }
            src["max_tokens"]?.let { put("max_tokens", it) }
            src["temperature"]?.let { put("temperature", it) }
            src["top_p"]?.let { put("top_p", it) }
            src["stream"]?.let {
                put("stream", it)
                if (it.jsonPrimitive.booleanOrNull == true) {
                    put("stream_options", buildJsonObject { put("include_usage", true) })
                }
            }
            (src["stop_sequences"] as? JsonArray)?.let { put("stop", it) }

            put("messages", buildMessages(src))

            (src["tools"] as? JsonArray)?.let { put("tools", convertTools(it)) }
            src["tool_choice"]?.let { put("tool_choice", convertToolChoice(it)) }
        }
        return out.toString()
    }

    private fun buildMessages(src: JsonObject): JsonArray = buildJsonArray {
        // system → 首条 system 消息
        src["system"]?.let { sys ->
            val text = when (sys) {
                is JsonPrimitive -> sys.contentOrNull ?: ""
                is JsonArray -> sys.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("\n")
                else -> ""
            }
            if (text.isNotEmpty()) add(buildJsonObject { put("role", "system"); put("content", text) })
        }
        for (m in src["messages"]!!.jsonArray) {
            val msg = m.jsonObject
            val role = msg["role"]!!.jsonPrimitive.content
            val content = msg["content"]!!
            addAll(convertMessage(role, content))
        }
    }

    /** 一条 Anthropic 消息可能展开成多条 OpenAI 消息（tool_result 单列为 role=tool）。 */
    private fun convertMessage(role: String, content: JsonElement): List<JsonObject> {
        if (content is JsonPrimitive) {
            return listOf(buildJsonObject { put("role", role); put("content", content) })
        }
        val blocks = content.jsonArray
        val texts = mutableListOf<String>()
        val imageParts = mutableListOf<JsonObject>()
        val toolCalls = mutableListOf<JsonObject>()
        val toolResults = mutableListOf<JsonObject>()

        for (b in blocks) {
            val block = b.jsonObject
            when (block["type"]?.jsonPrimitive?.content) {
                "text" -> texts += block["text"]?.jsonPrimitive?.contentOrNull ?: ""
                "image" -> {
                    val s = block["source"]?.jsonObject
                    val media = s?.get("media_type")?.jsonPrimitive?.contentOrNull ?: "image/png"
                    val data = s?.get("data")?.jsonPrimitive?.contentOrNull ?: ""
                    imageParts += buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", "data:$media;base64,$data") })
                    }
                }
                "tool_use" -> toolCalls += buildJsonObject {
                    put("id", block["id"] ?: JsonPrimitive(""))
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", block["name"] ?: JsonPrimitive(""))
                        put("arguments", JsonPrimitive((block["input"] ?: buildJsonObject {}).toString()))
                    })
                }
                "tool_result" -> {
                    val tc = block["content"]
                    val text = when (tc) {
                        is JsonPrimitive -> tc.contentOrNull ?: ""
                        is JsonArray -> tc.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("\n")
                        else -> tc?.toString() ?: ""
                    }
                    toolResults += buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", block["tool_use_id"] ?: JsonPrimitive(""))
                        put("content", text)
                    }
                }
            }
        }

        val result = mutableListOf<JsonObject>()
        // 主消息：文本（+ 图片走 content 数组）+ tool_calls
        if (imageParts.isNotEmpty()) {
            val parts = buildJsonArray {
                if (texts.isNotEmpty()) add(buildJsonObject { put("type", "text"); put("text", texts.joinToString("\n")) })
                imageParts.forEach { add(it) }
            }
            result += buildJsonObject { put("role", role); put("content", parts) }
        } else if (texts.isNotEmpty() || toolCalls.isNotEmpty()) {
            result += buildJsonObject {
                put("role", role)
                put("content", if (texts.isNotEmpty()) JsonPrimitive(texts.joinToString("\n")) else JsonNull)
                if (toolCalls.isNotEmpty()) put("tool_calls", JsonArray(toolCalls))
            }
        }
        result += toolResults
        return result
    }

    private fun convertTools(tools: JsonArray): JsonArray = buildJsonArray {
        for (t in tools) {
            val tool = t.jsonObject
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", tool["name"] ?: JsonPrimitive(""))
                    tool["description"]?.let { put("description", it) }
                    put("parameters", tool["input_schema"] ?: buildJsonObject { put("type", "object") })
                })
            })
        }
    }

    private fun convertToolChoice(choice: JsonElement): JsonElement = when {
        choice is JsonObject -> when (choice["type"]?.jsonPrimitive?.content) {
            "auto" -> JsonPrimitive("auto")
            "any" -> JsonPrimitive("required")
            "tool" -> buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject { put("name", choice["name"] ?: JsonPrimitive("")) })
            }
            else -> JsonPrimitive("auto")
        }
        else -> JsonPrimitive("auto")
    }
}
