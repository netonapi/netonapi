package logic.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResponsesBridgeTest {
    private fun o(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun statelessRequestsBridgeAndStatefulOnesStayOnOpenAi() {
        assertNull(ResponsesBridge.rejection("""{"model":"m","input":"hi","instructions":"be brief","tools":[{"type":"function","name":"f","parameters":{"type":"object"}}]}"""))
        assertNotNull(ResponsesBridge.rejection("""{"model":"m","input":"hi","previous_response_id":"resp_1"}"""))
        assertNotNull(ResponsesBridge.rejection("""{"model":"m","input":"hi","background":true}"""))
        assertTrue(ResponsesBridge.rejection("""{"model":"m","input":"hi","tools":[{"type":"web_search"}]}""")!!.contains("hosted_tool_omitted"))
    }

    @Test
    fun responsesToAnthropicAndBack() {
        val hub = ProtocolCodecs.requestToHub(ResponsesBridge.PROTOCOL)("""{"model":"claude","input":"hi","instructions":"be brief","max_output_tokens":64,"stream":true}""")
        val anthropic = o(ProtocolCodecs.requestFromHub("anthropic")(hub))
        assertEquals("be brief", anthropic["system"]!!.jsonPrimitive.content)
        assertEquals(64, anthropic["max_tokens"]!!.jsonPrimitive.content.toInt())

        val hubResp = ProtocolCodecs.responseToHub("anthropic")(
            """{"id":"msg_1","type":"message","role":"assistant","model":"claude","content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":1}}""")
        val resp = o(ProtocolCodecs.responseFromHub(ResponsesBridge.PROTOCOL)(hubResp))
        assertEquals("response", resp["object"]!!.jsonPrimitive.content)
        assertEquals("completed", resp["status"]!!.jsonPrimitive.content)
        val text = resp["output"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("hello", text)

        val stage = ProtocolCodecs.streamFromHub(ResponsesBridge.PROTOCOL, "claude")!!
        val out = stage.feed("data: {\"id\":\"c\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\ndata: {\"id\":\"c\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n") + stage.finish()
        assertTrue("response.output_text.delta" in out && "response.completed" in out, out)
    }
}
