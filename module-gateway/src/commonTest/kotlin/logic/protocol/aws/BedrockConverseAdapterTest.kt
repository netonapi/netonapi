package logic.protocol.aws

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logic.Usage
import logic.protocol.ProtocolCodecs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BedrockConverseAdapterTest {
    private val json = Json

    @Test
    fun hubRequestBecomesConverse() {
        val hub = """{"model":"amazon.nova-pro-v1:0","max_tokens":50,"messages":[{"role":"system","content":"s"},{"role":"user","content":"hi"}],"stream":true}"""
        val body = BedrockConverseAdapter.upstreamBody(ProtocolCodecs.requestFromHub("bedrock_converse")(hub), "amazon.nova-pro-v1:0", true)
        val o = json.parseToJsonElement(body).jsonObject
        assertEquals("s", o["system"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(50, o["inferenceConfig"]!!.jsonObject["maxTokens"]!!.jsonPrimitive.content.toInt())
        assertFalse("modelId" in o || "model" in o || "stream" in o)
        assertEquals("/model/amazon.nova-pro-v1%3A0/converse-stream",
            BedrockConverseAdapter.upstreamPath("/v1/chat/completions", model.Channel(id = 1, name = "c", type = "bedrock_converse", baseUrl = "https://bedrock-runtime.us-east-1.amazonaws.com"), "amazon.nova-pro-v1:0", true))
    }

    @Test
    fun usageNonStreamAndStream() {
        assertEquals(Usage(10, 3, 4, 0), BedrockConverseAdapter.extractUsage(
            """{"output":{"message":{"role":"assistant","content":[{"text":"ok"}]}},"stopReason":"end_turn","usage":{"inputTokens":10,"outputTokens":3,"cacheReadInputTokens":4}}"""))
        val acc = BedrockConverseAdapter.streamUsageAccumulator("{}")
        acc.feed("event: messageStart\ndata: {\"role\":\"assistant\"}\n\n")
        acc.feed("event: contentBlockDelta\ndata: {\"contentBlockIndex\":0,\"delta\":{\"text\":\"hi\"}}\n\n")
        assertFalse(acc.terminalSeen())
        acc.feed("event: messageStop\ndata: {\"stopReason\":\"end_turn\"}\n\nevent: metadata\ndata: {\"usage\":{\"inputTokens\":7,\"outputTokens\":2,\"cacheWriteInputTokens\":5}}\n\n")
        assertTrue(acc.terminalSeen())
        assertEquals(Usage(7, 2, 0, 5), acc.usage())
    }
}
