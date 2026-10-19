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
}
