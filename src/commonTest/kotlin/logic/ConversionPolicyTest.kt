package logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logic.protocol.ProtocolCodecs
import model.Channel
import neton.ai.protocol.diag.ConversionRejectedException
import neton.ai.protocol.diag.Diagnostic
import neton.ai.protocol.diag.DiagnosticCodes
import neton.ai.protocol.diag.LossPolicy
import neton.ai.protocol.ir.ReasoningOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConversionPolicyTest {
    private fun ch(policy: String) = Channel(id = 1, name = "c", type = "openai_compatible", baseUrl = "https://x", lossPolicy = policy)

    // Claude Code 的一轮：带 web_search 托管工具，转到 OpenAI 系上游会丢掉它（error 级）
    private val anthropic = """{"model":"m","max_tokens":10,"messages":[{"role":"user","content":"hi"}],
        "tools":[{"name":"get","input_schema":{"type":"object"}},{"type":"web_search_20250305","name":"web_search"}]}"""

    @Test
    fun policiesDecideWhetherALossyConversionProceeds() {
        val diags = mutableListOf<Diagnostic>()
        ProtocolCodecs.convertRequest(anthropic, "anthropic", "openai", ConversionPolicy.of(ch("allow")), diags)
        assertTrue(diags.any { it.code == DiagnosticCodes.HOSTED_TOOL_OMITTED })
        assertFailsWith<ConversionRejectedException> {
            ProtocolCodecs.convertRequest(anthropic, "anthropic", "openai", ConversionPolicy.of(ch("safe")), mutableListOf())
        }
        assertFailsWith<IllegalArgumentException> { ConversionPolicy.requirePolicy("lenient") }
        assertEquals("safe", ConversionPolicy.requirePolicy(" SAFE "))
    }

    @Test
    fun safeAllowsTheGeminiBypassOnlyWhenItIsActuallyOn() {
        val on = ConversionPolicy.of(ch("safe"), signatureBypass = true)
        assertEquals(setOf(DiagnosticCodes.GEMINI_THOUGHT_SIGNATURE_BYPASSED), (on as LossPolicy.Safe).allowedLosses)
        assertIs<LossPolicy.Strict>(ConversionPolicy.of(ch("strict"), signatureBypass = true))
    }

    @Test
    fun reasoningSuffixIsWrittenInTheClientsProtocol() {
        val oa = Json.parseToJsonElement(ProtocolCodecs.withReasoning("openai",
            """{"model":"gpt-5-high","messages":[{"role":"user","content":"hi"}]}""", "gpt-5", ReasoningOptions(effort = "high"))).jsonObject
        assertEquals("gpt-5", oa["model"]!!.jsonPrimitive.content)
        assertEquals("high", oa["reasoning_effort"]!!.jsonPrimitive.content)
        val an = Json.parseToJsonElement(ProtocolCodecs.withReasoning("anthropic",
            """{"model":"claude-thinking-4000","max_tokens":8000,"messages":[{"role":"user","content":"hi"}]}""", "claude",
            neton.ai.protocol.convert.ReasoningSuffix.parse("claude-thinking-4000").reasoning!!)).jsonObject
        assertEquals(4000, an["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content.toInt())
        val ge = Json.parseToJsonElement(ProtocolCodecs.withReasoning("gemini",
            """{"model":"gemini-3-pro-nothinking","contents":[{"role":"user","parts":[{"text":"hi"}]}]}""", "gemini-3-pro",
            ReasoningOptions(mode = neton.ai.protocol.ir.ReasoningMode.Disabled))).jsonObject
        assertEquals("gemini-3-pro", ge["model"]!!.jsonPrimitive.content)
        assertEquals(0, ge["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.content.toInt())
    }
}
