package logic

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PriceCalibrationParseTest {

    @Test
    fun liteLlmPerTokenPricesBecomePerMillion() {
        val root = Json.parseToJsonElement(
            """{"sample_spec":{"input_cost_per_token":0},
               "gpt-4o":{"input_cost_per_token":2.5e-06,"output_cost_per_token":1e-05,"cache_read_input_token_cost":1.25e-06,"max_output_tokens":16384},
               "embed-only":{"mode":"embedding"}}""",
        )
        val list = PriceCalibrationLogic.parseLiteLlm(root)
        assertEquals(1, list.size, "sample_spec 与没价格的条目都要跳过")
        val p = list.single()
        assertEquals("gpt-4o", p.key)
        assertEquals("2.5", p.input)
        assertEquals("10", p.output)
        assertEquals("1.25", p.cacheRead)
        assertNull(p.cacheWrite)
        assertEquals(16384, p.maxOutput)
    }

    @Test
    fun openRouterStringPricesAndVariablePricing() {
        val root = Json.parseToJsonElement(
            """{"data":[
                {"id":"anthropic/claude-sonnet-4","pricing":{"prompt":"0.000003","completion":"0.000015","input_cache_read":"0.0000003"}},
                {"id":"openrouter/auto","pricing":{"prompt":"-1","completion":"-1"}}]}""",
        )
        val list = PriceCalibrationLogic.parseOpenRouter(root)
        assertEquals(listOf("anthropic/claude-sonnet-4"), list.map { it.key }, "-1（可变价）不当官方价")
        assertEquals("3", list.single().input)
        assertEquals("15", list.single().output)
        assertEquals("0.3", list.single().cacheRead)
    }

    @Test
    fun basellmBillingExpressions() {
        val root = Json.parseToJsonElement(
            """{"success":true,"data":{"billing_mode":{"a":"tiered_expr","bad":"tiered_expr"},
               "billing_expr":{"a":"tier(\"standard\", p * 2.5 + c * 10)","bad":"foo(p)"}}}""",
        )
        val (ok, bad) = PriceCalibrationLogic.parseBasellm(root)
        assertEquals(listOf("a"), ok.map { it.key })
        assertEquals("2.5", ok.single().input)
        assertEquals(listOf("bad"), bad)
    }

    @Test
    fun modelsDevPrefersFirstPartyProvider() {
        val root = Json.parseToJsonElement(
            """{"zz-reseller":{"models":{"claude-x":{"cost":{"input":1,"output":2}}}},
               "anthropic":{"models":{"claude-x":{"cost":{"input":3,"output":15,"cache_read":0.3,"cache_write":3.75}}}}}""",
        )
        val p = PriceCalibrationLogic.parseModelsDev(root).single { it.key == "claude-x" }
        assertEquals("3", p.input)
        assertEquals("3.75", p.cacheWrite)
    }

    @Test
    fun sourceWithoutTierInfoRescalesExistingTiers() {
        val p = model.ModelPrice(model = "g", inputPrice = "1.25", outputPrice = "10",
            tiers = """[{"maxPromptTokens":200000,"input":"1.25","output":"10","cacheRead":"0","cacheWrite":"0"},
                        {"maxPromptTokens":null,"input":"2.5","output":"15","cacheRead":"0","cacheWrite":"0"}]""")
        val next = PriceCalibrationLogic.mergeSource(p, SourcePrice("g", input = "2.5", output = "20"))
        assertEquals("2.5", next.inputPrice, "基础价按价源更新，不能被旧的第一档盖回去")
        val tiers = PricingTiers.parse(next.tiers)!!
        assertEquals("5", tiers[1].input)
        assertEquals("30", tiers[1].output)
    }
}
