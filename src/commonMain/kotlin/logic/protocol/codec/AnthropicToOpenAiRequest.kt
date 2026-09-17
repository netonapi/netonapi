package logic.protocol.codec

import kotlinx.serialization.json.*

/**
 * Anthropic Messages 请求 → OpenAI ChatCompletions 请求。
 *
 * 这是 Claude Code / Anthropic SDK 打 OpenAI 系上游（DeepSeek、GLM、Kimi、任何兼容站）的路径，
 * 所以工具调用与多轮历史必须逐字段对得上：
 * - tool_result 必须紧跟在带 tool_calls 的 assistant 之后，所以同一条 user 消息里的 tool_result
 *   先出、文本/图片后出（Anthropic 允许混在一条里，OpenAI 不允许）；
 * - 没有 input_schema 的是 Anthropic 服务端工具（web_search、computer 等），OpenAI 上游跑不了，丢弃；
 * - thinking / redacted_thinking 历史块对 OpenAI 没意义，丢弃；thinking 参数折成 reasoning_effort；
 * - 图片 base64 与 url 两种来源都转成 image_url。
 */
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
            (src["metadata"] as? JsonObject)?.get("user_id")?.let { put("user", it) }
            (src["thinking"] as? JsonObject)?.let { th ->
                if (th["type"]?.jsonPrimitive?.contentOrNull == "enabled") {
                    CodecSupport.reasoningEffort(th["budget_tokens"]?.jsonPrimitive?.intOrNull)?.let { put("reasoning_effort", it) }
                }
            }
            put("messages", buildMessages(src))
            (src["tools"] as? JsonArray)?.let { tools ->
                val converted = convertTools(tools)
                if (converted.isNotEmpty()) put("tools", converted)
            }
            (src["tool_choice"] as? JsonObject)?.let { tc ->
                put("tool_choice", convertToolChoice(tc))
                if (tc["disable_parallel_tool_use"]?.jsonPrimitive?.booleanOrNull == true) put("parallel_tool_calls", false)
            }
        }
        return out.toString()
    }

    private fun buildMessages(src: JsonObject): JsonArray = buildJsonArray {
        // system → 首条 system 消息（数组形式只取 text，cache_control 之类丢掉）
        val sys = CodecSupport.textOf(src["system"])
        if (sys.isNotEmpty()) add(buildJsonObject { put("role", "system"); put("content", sys) })
        for (m in src["messages"]!!.jsonArray) {
            val msg = m.jsonObject
            val role = msg["role"]!!.jsonPrimitive.content
            val content = msg["content"] ?: JsonPrimitive("")
            addAll(convertMessage(role, content))
        }
    }

    /** 一条 Anthropic 消息可能展开成多条 OpenAI 消息（tool_result 单列为 role=tool，且排在前面）。 */
    private fun convertMessage(role: String, content: JsonElement): List<JsonObject> {
        if (content is JsonPrimitive) {
            return listOf(buildJsonObject { put("role", role); put("content", content) })
        }
        val texts = mutableListOf<String>()
        val imageParts = mutableListOf<JsonObject>()
        val toolCalls = mutableListOf<JsonObject>()
        val toolResults = mutableListOf<JsonObject>()
        for (b in content.jsonArray) {
            val block = b as? JsonObject ?: continue
            when (block["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> texts += block["text"]?.jsonPrimitive?.contentOrNull ?: ""
                "image" -> CodecSupport.fromAnthropicImage(block)?.let { imageParts += CodecSupport.openAiImage(it) }
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
                        null, is JsonNull -> ""
                        is JsonPrimitive -> tc.contentOrNull ?: ""
                        is JsonArray -> tc.mapNotNull { p ->
                            val o = p as? JsonObject ?: return@mapNotNull null
                            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                                "text" -> o["text"]?.jsonPrimitive?.contentOrNull
                                "image" -> "[image]"
                                else -> null
                            }
                        }.joinToString("\n")
                        else -> tc.toString()
                    }
                    toolResults += buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", block["tool_use_id"] ?: JsonPrimitive(""))
                        put("content", text)
                    }
                }
                // thinking / redacted_thinking：OpenAI 上游没有对应概念，丢弃
            }
        }
        val result = mutableListOf<JsonObject>()
        result += toolResults
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
        return result
    }

    private fun convertTools(tools: JsonArray): JsonArray = buildJsonArray {
        for (t in tools) {
            val tool = t as? JsonObject ?: continue
            val schema = tool["input_schema"] as? JsonObject ?: continue  // 服务端工具（无 schema）跳过
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", tool["name"] ?: JsonPrimitive(""))
                    tool["description"]?.let { put("description", it) }
                    put("parameters", schema)
                })
            })
        }
    }

    private fun convertToolChoice(choice: JsonObject): JsonElement = when (choice["type"]?.jsonPrimitive?.contentOrNull) {
        "any" -> JsonPrimitive("required")
        "none" -> JsonPrimitive("none")
        "tool" -> buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject { put("name", choice["name"] ?: JsonPrimitive("")) })
        }
        else -> JsonPrimitive("auto")
    }
}
