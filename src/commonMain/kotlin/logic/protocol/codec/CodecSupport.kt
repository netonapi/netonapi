package logic.protocol.codec

import kotlinx.serialization.json.*

/** 三种协议之间共用的小工具：内容块归一、data URL 拆解、reasoning_effort ↔ thinking 预算。 */
internal object CodecSupport {
    /** OpenAI 的 content 可能是字符串、null 或 parts 数组；统一成 (texts, images) 两组。 */
    data class Parts(val texts: List<String>, val images: List<JsonObject>)

    /** image：{"kind":"base64","media":…,"data":…} 或 {"kind":"url","url":…}。 */
    fun openAiParts(content: JsonElement?): Parts {
        if (content == null || content is JsonNull) return Parts(emptyList(), emptyList())
        if (content is JsonPrimitive) return Parts(listOf(content.contentOrNull ?: ""), emptyList())
        val texts = mutableListOf<String>(); val images = mutableListOf<JsonObject>()
        for (p in content.jsonArray) {
            val part = p as? JsonObject ?: continue
            when (part["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> part["text"]?.jsonPrimitive?.contentOrNull?.let { texts += it }
                "image_url" -> {
                    val url = (part["image_url"] as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
                        ?: part["image_url"]?.jsonPrimitive?.contentOrNull ?: continue
                    images += imageFromUrl(url)
                }
            }
        }
        return Parts(texts, images)
    }

    fun imageFromUrl(url: String): JsonObject = buildJsonObject {
        val m = DATA_URL.matchEntire(url)
        if (m != null) {
            put("kind", "base64"); put("media", m.groupValues[1]); put("data", m.groupValues[2])
        } else {
            put("kind", "url"); put("url", url)
        }
    }

    /** 归一后的 image → Anthropic image 块。 */
    fun anthropicImage(img: JsonObject): JsonObject = buildJsonObject {
        put("type", "image")
        put("source", if (img["kind"]?.jsonPrimitive?.content == "url") buildJsonObject {
            put("type", "url"); put("url", img["url"]!!)
        } else buildJsonObject {
            put("type", "base64"); put("media_type", img["media"]!!); put("data", img["data"]!!)
        })
    }

    /** 归一后的 image → OpenAI image_url 块。 */
    fun openAiImage(img: JsonObject): JsonObject = buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject {
            put("url", if (img["kind"]?.jsonPrimitive?.content == "url") img["url"]!!
                else JsonPrimitive("data:${img["media"]!!.jsonPrimitive.content};base64,${img["data"]!!.jsonPrimitive.content}"))
        })
    }

    /** Anthropic image 块 → 归一 image。 */
    fun fromAnthropicImage(block: JsonObject): JsonObject? {
        val s = block["source"] as? JsonObject ?: return null
        return when (s["type"]?.jsonPrimitive?.contentOrNull) {
            "url" -> buildJsonObject { put("kind", "url"); put("url", s["url"] ?: return null) }
            else -> buildJsonObject {
                put("kind", "base64")
                put("media", s["media_type"] ?: JsonPrimitive("image/png"))
                put("data", s["data"] ?: JsonPrimitive(""))
            }
        }
    }

    /** OpenAI reasoning_effort → Anthropic thinking 预算（tokens）。 */
    fun thinkingBudget(effort: String?): Int? = when (effort) {
        "minimal", "low" -> 1024
        "medium" -> 8192
        "high" -> 16384
        else -> null
    }

    /** Anthropic thinking 预算 → OpenAI reasoning_effort。 */
    fun reasoningEffort(budget: Int?): String? = when {
        budget == null || budget <= 0 -> null
        budget <= 2048 -> "low"
        budget <= 8192 -> "medium"
        else -> "high"
    }

    /** JsonElement 是字符串就取字符串，是数组就把 text 块拼起来。 */
    fun textOf(el: JsonElement?): String = when (el) {
        null, is JsonNull -> ""
        is JsonPrimitive -> el.contentOrNull ?: ""
        is JsonArray -> el.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("\n")
        is JsonObject -> el["text"]?.jsonPrimitive?.contentOrNull ?: el.toString()
    }

    private val DATA_URL = Regex("^data:([^;,]+);base64,(.*)$", RegexOption.DOT_MATCHES_ALL)
}
