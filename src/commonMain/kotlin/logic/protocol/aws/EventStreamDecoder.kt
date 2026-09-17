package logic.protocol.aws

import kotlinx.serialization.json.*
import logic.protocol.ByteStreamDecoder

/**
 * AWS event-stream（application/vnd.amazon.eventstream）→ SSE 文本。
 * 帧：total_len(4) headers_len(4) prelude_crc(4) headers payload msg_crc(4)；
 * header：name_len(1) name type(1) value（type 7 = 字符串：len(2)+bytes）。
 * Bedrock 的 chunk 事件 payload 是 `{"bytes":"<base64 的 Anthropic 事件 JSON>"}`，解出来按
 * `event: <type>\ndata: <json>\n\n` 放出；异常帧（:exception-type）转成 error 事件。CRC 不校验：TCP 已保证完整性。
 */
class EventStreamDecoder : ByteStreamDecoder {
    private var buf = ByteArray(0)
    private val json = Json { ignoreUnknownKeys = true }

    override fun feed(bytes: ByteArray): String {
        buf += bytes
        val out = StringBuilder()
        while (true) {
            if (buf.size < 12) break
            val total = readInt(buf, 0)
            if (total < 16 || total > 16 * 1024 * 1024) { buf = ByteArray(0); break }  // 帧头损坏：丢弃缓冲，不无限等
            if (buf.size < total) break
            val headersLen = readInt(buf, 4)
            val headers = parseHeaders(buf, 12, headersLen)
            val payload = buf.copyOfRange(12 + headersLen, total - 4)
            buf = buf.copyOfRange(total, buf.size)
            out.append(render(headers, payload))
        }
        return out.toString()
    }

    override fun finish(): String = ""

    private fun render(headers: Map<String, String>, payload: ByteArray): String {
        val messageType = headers[":message-type"] ?: "event"
        val text = payload.decodeToString()
        if (messageType != "event") {
            val et = headers[":exception-type"] ?: headers[":error-code"] ?: "error"
            val msg = runCatching { json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: text
            return "event: error\ndata: " + buildJsonObject {
                put("type", "error"); put("error", buildJsonObject { put("type", et); put("message", msg) })
            }.toString() + "\n\n"
        }
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return ""
        val inner = obj["bytes"]?.jsonPrimitive?.contentOrNull?.let { b64 ->
            runCatching { decodeBase64(b64).decodeToString() }.getOrNull()
        } ?: text
        val type = runCatching { json.parseToJsonElement(inner).jsonObject["type"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?: headers[":event-type"] ?: "message"
        return "event: $type\ndata: $inner\n\n"
    }

    private fun parseHeaders(b: ByteArray, start: Int, len: Int): Map<String, String> {
        val out = HashMap<String, String>()
        var i = start; val end = start + len
        while (i < end) {
            val nameLen = b[i].toInt() and 0xff; i += 1
            val name = b.copyOfRange(i, i + nameLen).decodeToString(); i += nameLen
            val type = b[i].toInt() and 0xff; i += 1
            when (type) {
                0, 1 -> {}                                   // bool true/false
                2 -> i += 1; 3 -> i += 2; 4 -> i += 4; 5 -> i += 8   // int8/16/32/64
                6, 7 -> { val l = readShort(b, i); i += 2; val v = b.copyOfRange(i, i + l); i += l; if (type == 7) out[name] = v.decodeToString() }
                8 -> i += 8                                  // timestamp
                9 -> i += 16                                 // uuid
                else -> return out
            }
        }
        return out
    }

    private fun readInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

    private fun readShort(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

    companion object {
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        fun decodeBase64(s: String): ByteArray = kotlin.io.encoding.Base64.decode(s)
    }
}
