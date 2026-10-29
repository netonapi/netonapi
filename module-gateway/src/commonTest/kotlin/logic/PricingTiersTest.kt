package logic

import model.ModelPrice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class PricingTiersTest {

    private val tiered = ModelPrice(
        model = "m", inputPrice = "1.25", outputPrice = "10", cacheReadPrice = "0.125", cacheWritePrice = "0",
        tiers = """[{"maxPromptTokens":200000,"input":"1.25","output":"10","cacheRead":"0.125","cacheWrite":"0"},
                    {"maxPromptTokens":null,"input":"2.5","output":"15","cacheRead":"0.25","cacheWrite":"0"}]""",
    )

    @Test
    fun selectsLowTierAtExactThreshold() {
        assertEquals("1.25", PricingTiers.select(tiered, 200_000).inputPrice)
        val high = PricingTiers.select(tiered, 200_001)
        assertEquals("2.5", high.inputPrice)
        assertEquals("15", high.outputPrice)
        assertNull(high.tiers, "选档后的价格是单档的")
    }

    @Test
    fun noTiersReturnsSamePrice() {
        val flat = ModelPrice(model = "m", inputPrice = "3", outputPrice = "4")
        assertEquals(flat, PricingTiers.select(flat, 10_000_000))
    }

    @Test
    fun rejectsNonIncreasingOrOpenMiddleTier() {
        assertFails {
            PricingTiers.parse("""[{"maxPromptTokens":100,"input":"1","output":"1","cacheRead":"0","cacheWrite":"0"},
                                   {"maxPromptTokens":100,"input":"2","output":"2","cacheRead":"0","cacheWrite":"0"}]""")
        }
        assertFails {
            PricingTiers.parse("""[{"maxPromptTokens":null,"input":"1","output":"1","cacheRead":"0","cacheWrite":"0"},
                                   {"maxPromptTokens":100,"input":"2","output":"2","cacheRead":"0","cacheWrite":"0"}]""")
        }
        assertFails { PricingTiers.parse("""[{"maxPromptTokens":null,"input":"1","output":"1","cacheRead":"0","cacheWrite":"0"}]""") }
        assertNull(PricingTiers.parse("  "))
    }

    @Test
    fun mostExpensivePicksHighestTier() {
        assertEquals("2.5", PricingTiers.mostExpensive(tiered).inputPrice)
    }

    @Test
    fun totalInputCountsCacheOnce() {
        assertEquals(1050L, PricingTiers.totalInput(Usage(promptTokens = 1000, cacheReadTokens = 800, cacheWriteTokens = 50)))
    }

    @Test
    fun totalInputAddsCacheWhenPromptExcludesIt() {
        // Anthropic：input_tokens 不含缓存
        val u = Usage(promptTokens = 120_000, cacheReadTokens = 150_000, cacheWriteTokens = 10, promptIncludesCache = false)
        assertEquals(270_010L, PricingTiers.totalInput(u))
    }
}
