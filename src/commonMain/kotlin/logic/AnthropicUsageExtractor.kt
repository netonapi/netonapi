package logic

import kotlinx.serialization.json.*
import logic.protocol.StreamUsageAccumulator

/** Anthropic Messages usage 提取（非流式 usage{} + 流式 message_start/message_delta）。 */
object AnthropicUsageExtractor {

    private val json = Json { ignoreUnknownKeys = true }

    fun fromJson(body: String): Usage? {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return fromUsageObject(obj["usage"] as? JsonObject ?: return null)
    }

    fun fromUsageObject(usage: JsonObject): Usage = Usage(
        promptTokens = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
        completionTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
        cacheReadTokens = usage["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
        cacheWriteTokens = usage["cache_creation_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
    )

    fun accumulator(): StreamUsageAccumulator = AnthropicSseUsageAccumulator()
}

/**
 * Anthropic 流式 usage 累加：message_start.message.usage 给 input（+ 可能的 cache），
 * message_delta.usage 给 output（累积取最后）。合并成一个 Usage。
 */
class AnthropicSseUsageAccumulator : StreamUsageAccumulator {
    private val buffer = StringBuilder()
    private val json = Json { ignoreUnknownKeys = true }
    private var input = 0
    private var output = 0
    private var cacheRead = 0
    private var cacheWrite = 0
    private var seen = false
    private var terminal = false

    override fun feed(chunk: String) {
        buffer.append(chunk)
        while (true) {
            val idx = buffer.indexOf("\n\n")
            if (idx < 0) break
            val event = buffer.substring(0, idx)
            buffer.deleteRange(0, idx + 2)
            for (line in event.lineSequence()) {
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty() || data == line || data == "[DONE]") continue
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                // message_stop = 协议终止标记
                if (obj["type"]?.jsonPrimitive?.contentOrNull == "message_stop") terminal = true
                // message_start: {"type":"message_start","message":{"usage":{"input_tokens":..}}}
                (obj["message"] as? JsonObject)?.get("usage")?.let { applyUsage(it as? JsonObject) }
                // message_delta: {"type":"message_delta","usage":{"output_tokens":..}}
                (obj["usage"] as? JsonObject)?.let { applyUsage(it) }
            }
        }
    }

    override fun terminalSeen(): Boolean = terminal

    private fun applyUsage(u: JsonObject?) {
        if (u == null) return
        seen = true
        u["input_tokens"]?.jsonPrimitive?.intOrNull?.let { input = it }
        u["output_tokens"]?.jsonPrimitive?.intOrNull?.let { output = it }
        u["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull?.let { cacheRead = it }
        u["cache_creation_input_tokens"]?.jsonPrimitive?.intOrNull?.let { cacheWrite = it }
    }

    override fun usage(): Usage? =
        if (seen) Usage(input, output, cacheRead, cacheWrite) else null
}
