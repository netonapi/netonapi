package logic.protocol.codec

import kotlinx.serialization.json.*

/**
 * OpenAI ChatCompletions 请求 → Anthropic Messages 请求。
 *
 * - content 三种形态（字符串 / null / parts 数组）都认，image_url 的 data URL 拆成 base64 source，
 *   http(s) 地址走 url source；
 * - 连续的 tool 消息合并进同一条 user 消息（Anthropic 要求角色交替）；
 * - reasoning_effort 折成 thinking 预算，并保证 max_tokens 大于预算；
 * - max_completion_tokens 与 max_tokens 都认；user → metadata.user_id。
 */
object OpenAiToAnthropicRequest {
    private val json = Json { }
    private const val DEFAULT_MAX_TOKENS = 4096

    fun convert(openaiBody: String): String {
        val src = json.parseToJsonElement(openaiBody).jsonObject
        val systemParts = mutableListOf<String>()
        val messages = mergeAdjacent(buildAnthropicMessages(src["messages"]!!.jsonArray, systemParts))
        val budget = CodecSupport.thinkingBudget(src["reasoning_effort"]?.jsonPrimitive?.contentOrNull)
        val requestedMax = (src["max_completion_tokens"] ?: src["max_tokens"])?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_TOKENS
        val maxTokens = if (budget != null && requestedMax <= budget) budget + 1024 else requestedMax
        val out = buildJsonObject {
            src["model"]?.let { put("model", it) }
            put("max_tokens", maxTokens)
            if (budget != null) put("thinking", buildJsonObject { put("type", "enabled"); put("budget_tokens", budget) })
            // 开了 thinking 时 Anthropic 不允许 temperature/top_p 与之并存（temperature 必须为 1）
            if (budget == null) {
                src["temperature"]?.let { put("temperature", it) }
                src["top_p"]?.let { put("top_p", it) }
            }
            src["stream"]?.let { put("stream", it) }
            (src["stop"] as? JsonArray)?.let { put("stop_sequences", it) }
            (src["stop"] as? JsonPrimitive)?.let { put("stop_sequences", buildJsonArray { add(it) }) }
            src["user"]?.let { put("metadata", buildJsonObject { put("user_id", it) }) }
            if (systemParts.isNotEmpty()) put("system", systemParts.joinToString("\n"))
            put("messages", messages)
            (src["tools"] as? JsonArray)?.let { put("tools", convertTools(it)) }
            val choice = src["tool_choice"]?.let { convertToolChoice(it) }
            val noParallel = src["parallel_tool_calls"]?.jsonPrimitive?.booleanOrNull == false
            if (choice != null || noParallel) {
                put("tool_choice", buildJsonObject {
                    (choice ?: buildJsonObject { put("type", "auto") }).forEach { (k, v) -> put(k, v) }
                    if (noParallel) put("disable_parallel_tool_use", true)
                })
            }
        }
        return out.toString()
    }

    private fun buildAnthropicMessages(oaMessages: JsonArray, systemParts: MutableList<String>): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        for (m in oaMessages) {
            val msg = m.jsonObject
            when (msg["role"]?.jsonPrimitive?.contentOrNull) {
                "system", "developer" -> CodecSupport.textOf(msg["content"]).takeIf { it.isNotEmpty() }?.let { systemParts += it }
                "tool" -> result += buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", msg["tool_call_id"] ?: JsonPrimitive(""))
                            put("content", CodecSupport.textOf(msg["content"]))
                        })
                    })
                }
                "assistant" -> {
                    val parts = CodecSupport.openAiParts(msg["content"])
                    val toolCalls = msg["tool_calls"] as? JsonArray
                    val blocks = buildJsonArray {
                        parts.texts.joinToString("\n").takeIf { it.isNotEmpty() }?.let {
                            add(buildJsonObject { put("type", "text"); put("text", it) })
                        }
                        toolCalls?.forEach { tc ->
                            val call = tc.jsonObject
                            val fn = call["function"]?.jsonObject
                            val args = fn?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "{}"
                            add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", call["id"] ?: JsonPrimitive(""))
                                put("name", fn?.get("name") ?: JsonPrimitive(""))
                                put("input", runCatching { json.parseToJsonElement(args) }.getOrElse { buildJsonObject {} })
                            })
                        }
                    }
                    // Anthropic 不接受空 content 的 assistant 消息，空的直接略过
                    if (blocks.isNotEmpty()) result += buildJsonObject { put("role", "assistant"); put("content", blocks) }
                }
                else -> {
                    val parts = CodecSupport.openAiParts(msg["content"])
                    val blocks = buildJsonArray {
                        parts.texts.joinToString("\n").let { if (it.isNotEmpty() || parts.images.isEmpty()) add(buildJsonObject { put("type", "text"); put("text", it) }) }
                        parts.images.forEach { add(CodecSupport.anthropicImage(it)) }
                    }
                    result += buildJsonObject { put("role", "user"); put("content", blocks) }
                }
            }
        }
        return result
    }

    /** 相邻同角色消息合并（tool_result 串、tool_result 后紧跟的 user 文本）。 */
    private fun mergeAdjacent(list: List<JsonObject>): JsonArray = buildJsonArray {
        var cur: JsonObject? = null
        for (m in list) {
            if (cur != null && cur["role"] == m["role"]) {
                cur = buildJsonObject {
                    put("role", m["role"]!!)
                    put("content", JsonArray(cur!!["content"]!!.jsonArray + m["content"]!!.jsonArray))
                }
            } else {
                cur?.let { add(it) }
                cur = m
            }
        }
        cur?.let { add(it) }
    }

    private fun convertTools(tools: JsonArray): JsonArray = buildJsonArray {
        for (t in tools) {
            val fn = t.jsonObject["function"]?.jsonObject ?: continue
            add(buildJsonObject {
                put("name", fn["name"] ?: JsonPrimitive(""))
                fn["description"]?.let { put("description", it) }
                put("input_schema", fn["parameters"] ?: buildJsonObject { put("type", "object"); put("properties", buildJsonObject {}) })
            })
        }
    }

    private fun convertToolChoice(choice: JsonElement): JsonObject = when {
        choice is JsonPrimitive -> when (choice.contentOrNull) {
            "required" -> buildJsonObject { put("type", "any") }
            "none" -> buildJsonObject { put("type", "none") }
            else -> buildJsonObject { put("type", "auto") }
        }
        choice is JsonObject -> buildJsonObject {
            put("type", "tool")
            put("name", choice["function"]?.jsonObject?.get("name") ?: JsonPrimitive(""))
        }
        else -> buildJsonObject { put("type", "auto") }
    }
}
