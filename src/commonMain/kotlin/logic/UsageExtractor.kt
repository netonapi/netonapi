package logic

import kotlinx.serialization.json.*

/** 从上游响应提取 usage（OpenAI 兼容格式）。 */
object UsageExtractor {

    private val json = Json { ignoreUnknownKeys = true }

    fun fromJson(body: String): Usage? {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return fromUsageObject(obj["usage"] as? JsonObject ?: return null)
    }

    fun fromUsageObject(usage: JsonObject): Usage {
        val details = usage["prompt_tokens_details"] as? JsonObject
        return Usage(
            promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cacheReadTokens = details?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            cacheWriteTokens = 0,
        )
    }
}

/**
 * 流式直通时旁路累积 SSE 文本，记录最后一个含 usage 的 data 行（OpenAI include_usage 尾包）。
 * feed 输入与转发给客户端的块完全相同——只观察不修改。
 */
class SseUsageAccumulator {
    private val buffer = StringBuilder()
    private var last: Usage? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun feed(chunk: String) {
        buffer.append(chunk)
        // 按事件边界（空行）切；余量留在 buffer 处理撕裂
        while (true) {
            val idx = buffer.indexOf("\n\n")
            if (idx < 0) break
            val event = buffer.substring(0, idx)
            buffer.deleteRange(0, idx + 2)
            for (line in event.lineSequence()) {
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty() || data == line || data == "[DONE]") continue
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                (obj["usage"] as? JsonObject)?.let { last = UsageExtractor.fromUsageObject(it) }
            }
        }
    }

    fun usage(): Usage? = last
}
