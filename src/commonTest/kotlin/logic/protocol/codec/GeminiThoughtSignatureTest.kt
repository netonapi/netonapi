package logic.protocol.codec

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import logic.InMemoryThoughtSignatureStore
import logic.ThoughtSignatureVault
import logic.protocol.GeminiRequestOptions
import logic.protocol.GeminiThoughtSignatures
import logic.protocol.SignatureDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Gemini thought signature（neton-docs spec/ai.md §4.5；gateway 文档 §4.6）。
 * 关键是整条链路：Gemini 响应 → 客户端（OpenAI / Anthropic 格式）→ 下一轮请求 → Gemini 请求里签名还在。
 */
class GeminiThoughtSignatureTest {
    private val json = Json { }
    private fun obj(s: String) = json.parseToJsonElement(s).jsonObject

    private val geminiToolResponse = """{"candidates":[{"content":{"role":"model","parts":[
        {"functionCall":{"name":"get_weather","args":{"city":"Paris","unit":"c"}},"thoughtSignature":"SIG_A"},
        {"functionCall":{"name":"get_time","args":{"tz":"CET"}}}
      ]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":2}}"""

    private fun binding(tokenId: Long = 7, userId: Long = 9, model: String = "gemini-3-pro") =
        ThoughtSignatureVault.Binding(tokenId = tokenId, userId = userId, upstreamType = "gemini", upstreamModel = model, channelId = 1)

    // ---------- 响应方向：Gemini → hub ----------

    @Test fun geminiResponse_signatureGoesToOfficialExtraContentField() {
        val calls = obj(GeminiToOpenAiResponse.convert(geminiToolResponse))["choices"]!!.jsonArray[0]
            .jsonObject["message"]!!.jsonObject["tool_calls"]!!.jsonArray.map { it.jsonObject }
        assertEquals("SIG_A", GeminiThoughtSignatures.fromToolCall(calls[0]))
        // 并行调用：Google 只在第一个 functionCall 上给签名，其余保持没有
        assertNull(GeminiThoughtSignatures.fromToolCall(calls[1]))
    }

    @Test fun geminiStream_signatureGoesToExtraContentField() {
        val sm = GeminiToOpenAiStream("gemini-3-pro")
        val out = sm.feed("data: " + geminiToolResponse.replace("\n", "") + "\n\n")
        assertTrue(out.contains("\"thought_signature\":\"SIG_A\""), out)
    }

    // ---------- 请求方向：hub → Gemini ----------

    private fun hubWithHistory(firstCallSignature: String?) = """{"model":"m","messages":[
        {"role":"user","content":"weather?"},
        {"role":"assistant","content":null,"tool_calls":[
          {"id":"c1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Paris\"}"}${
              if (firstCallSignature != null) ""","extra_content":{"google":{"thought_signature":"$firstCallSignature"}}""" else ""}},
          {"id":"c2","type":"function","function":{"name":"get_time","arguments":"{}"}}
        ]},
        {"role":"tool","tool_call_id":"c1","content":"{\"t\":20}"},
        {"role":"tool","tool_call_id":"c2","content":"{\"t\":\"10:00\"}"}
      ]}"""

    private fun modelParts(geminiBody: String) = obj(geminiBody)["contents"]!!.jsonArray
        .map { it.jsonObject }.first { it["role"]!!.jsonPrimitive.content == "model" }["parts"]!!.jsonArray.map { it.jsonObject }

    @Test fun request_realSignatureIsWrittenBackUnchanged_evenWithBypassOn() {
        val diag = SignatureDiagnostics()
        val parts = modelParts(OpenAiToGeminiRequest.convert(hubWithHistory("REAL"), GeminiRequestOptions(signatureBypass = true), diag))
        assertEquals("REAL", parts[0]["thoughtSignature"]!!.jsonPrimitive.content)
        assertFalse(parts[1].containsKey("thoughtSignature"))
        assertEquals(0, diag.bypassed); assertEquals(0, diag.missingInCurrentTurn)
    }

    @Test fun request_bypassOnlyOnFirstFunctionCallOfStep_neverOnText() {
        val hub = hubWithHistory(null).replace("\"content\":null", "\"content\":\"let me check\"")
        val diag = SignatureDiagnostics()
        val parts = modelParts(OpenAiToGeminiRequest.convert(hub, GeminiRequestOptions(signatureBypass = true), diag))
        val text = parts.first { it.containsKey("text") }
        val calls = parts.filter { it.containsKey("functionCall") }
        assertFalse(text.containsKey("thoughtSignature"))          // 官方：文本签名非必需，占位值只用于 functionCall
        assertEquals(GeminiThoughtSignatures.BYPASS_VALUE, calls[0]["thoughtSignature"]!!.jsonPrimitive.content)
        assertFalse(calls[1].containsKey("thoughtSignature"))      // 只写每步第一个
        assertEquals(1, diag.bypassed)
    }

    @Test fun request_missingSignatureIsCountedInCurrentTurnOnly_whenBypassOff() {
        val diag = SignatureDiagnostics()
        OpenAiToGeminiRequest.convert(hubWithHistory(null), GeminiRequestOptions(signatureBypass = false), diag)
        assertEquals(1, diag.missingInCurrentTurn)

        // 历史轮次（后面又有一条用户消息）不校验，不算缺失
        val older = """{"model":"m","messages":[
            {"role":"user","content":"weather?"},
            {"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"get_weather","arguments":"{}"}}]},
            {"role":"tool","tool_call_id":"c1","content":"20"},
            {"role":"assistant","content":"20C"},
            {"role":"user","content":"thanks"}
          ]}"""
        val diag2 = SignatureDiagnostics()
        OpenAiToGeminiRequest.convert(older, GeminiRequestOptions(signatureBypass = false), diag2)
        assertEquals(0, diag2.missingInCurrentTurn)
    }

    // ---------- 状态引用 ----------

    @Test fun vault_issueReplacesIdWithUnguessableReference_andFlushStoresRecord() = runBlocking<Unit> {
        val store = InMemoryThoughtSignatureStore()
        val vault = ThoughtSignatureVault(binding(), store, 1.hours)
        val hub = vault.issue(GeminiToOpenAiResponse.convert(geminiToolResponse))
        val calls = obj(hub)["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["tool_calls"]!!.jsonArray.map { it.jsonObject }
        val id = calls[0]["id"]!!.jsonPrimitive.content
        val ref = assertNotNull(ThoughtSignatureVault.refOf(id))
        // 引用只含 [A-Za-z0-9]：满足 Anthropic tool_use id 的 ^[a-zA-Z0-9_-]+$
        assertTrue(Regex("^[a-zA-Z0-9_-]+$").matches(id))
        // 第二个调用没有签名，ID 不动
        assertEquals("call_get_time_1", calls[1]["id"]!!.jsonPrimitive.content)
        // 落库之前取不到；flush 之后才有（客户端拿到 ID 之前必须 flush）
        assertNull(store.get(ref))
        vault.flush()
        assertEquals("SIG_A", store.get(ref)!!.signature)
        // 两次签发的引用不同
        val again = ThoughtSignatureVault(binding(), store, 1.hours).issue(GeminiToOpenAiResponse.convert(geminiToolResponse))
        assertNotEquals(id, obj(again)["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test fun vault_canDropExtraContentForNonOpenAiClients() {
        val vault = ThoughtSignatureVault(binding(), InMemoryThoughtSignatureStore(), 1.hours, keepExtraContent = false)
        val hub = vault.issue(GeminiToOpenAiResponse.convert(geminiToolResponse))
        assertFalse(hub.contains("thought_signature"))
    }

    /** 整条链路：Anthropic 格式客户端，签名经状态引用回到下一轮的 Gemini 请求。 */
    @Test fun roundTrip_anthropicClient_signatureReturnsThroughToolUseId() = runBlocking<Unit> {
        val store = InMemoryThoughtSignatureStore()
        // 第一轮：Gemini 响应 → hub → 签发引用 → Anthropic 客户端
        val vault1 = ThoughtSignatureVault(binding(), store, 1.hours, keepExtraContent = false)
        val anthropicResponse = OpenAiToAnthropicResponse.convert(vault1.issue(GeminiToOpenAiResponse.convert(geminiToolResponse)))
        vault1.flush()
        assertFalse(anthropicResponse.contains("thought_signature"))      // 不塞进 Anthropic 的任何字段
        val toolUse = obj(anthropicResponse)["content"]!!.jsonArray.map { it.jsonObject }.first { it["type"]!!.jsonPrimitive.content == "tool_use" }
        val toolUseId = toolUse["id"]!!.jsonPrimitive.content

        // 第二轮：客户端把 assistant 历史（含 tool_use id）和工具结果发回来
        val anthropicRequest = """{"model":"gemini-3-pro","max_tokens":64,"messages":[
            {"role":"user","content":"weather in Paris?"},
            {"role":"assistant","content":[{"type":"tool_use","id":"$toolUseId","name":"get_weather","input":${toolUse["input"]}}]},
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"$toolUseId","content":"20C"}]}
          ]}"""
        val vault2 = ThoughtSignatureVault(binding(), store, 1.hours)
        val hub = vault2.resolve(AnthropicToOpenAiRequest.convert(anthropicRequest))
        val diag = SignatureDiagnostics()
        val gemini = OpenAiToGeminiRequest.convert(hub, GeminiRequestOptions(signatureBypass = false), diag)
        assertEquals("SIG_A", modelParts(gemini)[0]["thoughtSignature"]!!.jsonPrimitive.content)
        assertEquals(0, diag.missingInCurrentTurn)
    }

    /** OpenAI 客户端：既拿到官方 extra_content，也拿到引用 ID；原样带回 extra_content 时直接使用。 */
    @Test fun roundTrip_openAiClient_extraContentEchoIsUsedDirectly() = runBlocking<Unit> {
        val store = InMemoryThoughtSignatureStore()
        val vault1 = ThoughtSignatureVault(binding(), store, 1.hours, keepExtraContent = true)
        val toClient = obj(vault1.issue(GeminiToOpenAiResponse.convert(geminiToolResponse)))
        vault1.flush()
        val call = toClient["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("SIG_A", GeminiThoughtSignatures.fromToolCall(call))
        val hub = """{"model":"m","messages":[{"role":"user","content":"q"},
            {"role":"assistant","content":null,"tool_calls":[$call]},
            {"role":"tool","tool_call_id":${call["id"]},"content":"20"}]}"""
        val gemini = OpenAiToGeminiRequest.convert(ThoughtSignatureVault(binding(), store, 1.hours).resolve(hub))
        assertEquals("SIG_A", modelParts(gemini)[0]["thoughtSignature"]!!.jsonPrimitive.content)
    }

    @Test fun resolve_rejectsWhenArgumentsChanged_tokenDiffers_modelDiffers_orExpired() = runBlocking<Unit> {
        var clock = 0L
        val store = InMemoryThoughtSignatureStore(now = { clock })
        val vault1 = ThoughtSignatureVault(binding(), store, 10.minutes)
        val call = obj(vault1.issue(GeminiToOpenAiResponse.convert(geminiToolResponse)))["choices"]!!.jsonArray[0]
            .jsonObject["message"]!!.jsonObject["tool_calls"]!!.jsonArray[0].jsonObject
        vault1.flush()
        val id = call["id"]!!.jsonPrimitive.content
        fun hub(args: String) = """{"model":"m","messages":[{"role":"user","content":"q"},
            {"role":"assistant","content":null,"tool_calls":[{"id":"$id","type":"function","function":{"name":"get_weather","arguments":${JsonPrimitive(args)}}}]}]}"""
        val sameArgs = """{"unit":"c","city":"Paris"}"""          // 键顺序不同，仍视为同一调用
        suspend fun signed(v: ThoughtSignatureVault, args: String) =
            GeminiThoughtSignatures.fromToolCall(obj(v.resolve(hub(args)))["messages"]!!.jsonArray[1].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject)

        assertEquals("SIG_A", signed(ThoughtSignatureVault(binding(), store, 10.minutes), sameArgs))
        assertNull(signed(ThoughtSignatureVault(binding(), store, 10.minutes), """{"city":"Berlin","unit":"c"}"""))  // 参数被改
        assertNull(signed(ThoughtSignatureVault(binding(tokenId = 8), store, 10.minutes), sameArgs))               // 令牌不符
        assertNull(signed(ThoughtSignatureVault(binding(userId = 10), store, 10.minutes), sameArgs))               // 用户不符
        assertNull(signed(ThoughtSignatureVault(binding(model = "gemini-2.5-pro"), store, 10.minutes), sameArgs))  // 模型不符
        clock += 11.minutes.inWholeMilliseconds
        assertNull(signed(ThoughtSignatureVault(binding(), store, 10.minutes), sameArgs))                          // 过期
    }

    @Test fun refOf_onlyAcceptsGatewayIssuedFormat() {
        assertNull(ThoughtSignatureVault.refOf("call_get_weather_0"))
        assertNull(ThoughtSignatureVault.refOf(ThoughtSignatureVault.ID_PREFIX + "short"))
        assertNull(ThoughtSignatureVault.refOf(ThoughtSignatureVault.ID_PREFIX + "x".repeat(31) + "-"))
        assertNotNull(ThoughtSignatureVault.refOf(ThoughtSignatureVault.ID_PREFIX + "A1".repeat(16)))
    }

    @Test fun inMemoryStore_evictsOldestBeyondCapacity() = runBlocking<Unit> {
        val store = InMemoryThoughtSignatureStore(capacity = 2)
        val r = logic.SignatureRecord("s", "o", 1, 1, "gemini", "m", 1, "d")
        store.put("a", r, 1.hours); store.put("b", r, 1.hours); store.put("c", r, 1.hours)
        assertNull(store.get("a")); assertNotNull(store.get("b")); assertNotNull(store.get("c"))
    }

    @Test fun streamStage_rewritesToolCallIdInSseEvent() = runBlocking<Unit> {
        val store = InMemoryThoughtSignatureStore()
        val vault = ThoughtSignatureVault(binding(), store, 1.hours)
        val stage = vault.stage()
        val hubChunk = GeminiToOpenAiStream("gemini-3-pro").feed("data: " + geminiToolResponse.replace("\n", "") + "\n\n")
        // 分成两段喂入，验证跨块的事件拼接
        val mid = hubChunk.length / 2
        val out = stage.feed(hubChunk.substring(0, mid)) + stage.feed(hubChunk.substring(mid)) + stage.finish()
        val ids = Regex("\"id\":\"(call_ngs_[A-Za-z0-9]+)\"").findAll(out).map { it.groupValues[1] }.toList()
        assertEquals(1, ids.size, out)
        vault.flush()
        assertEquals("SIG_A", store.get(ThoughtSignatureVault.refOf(ids[0])!!)!!.signature)
    }
}
