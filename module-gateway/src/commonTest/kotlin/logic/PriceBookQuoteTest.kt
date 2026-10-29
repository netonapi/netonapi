package logic

import model.GatewayGroup
import model.ModelPrice
import model.PriceRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 定价规则 → 报价（spec §4.3）：以官方价为锚；命中折扣 / 固定价时不再乘分组倍率。 */
class PriceBookQuoteTest {

    private val official = ModelPrice(model = "gemini-2.5-flash", inputPrice = "0.3", outputPrice = "2.5", cacheReadPrice = "0.075")
    private val vip = GatewayGroup(code = "vip", name = "VIP", ratio = "0.9")
    private fun rule(mode: String, discount: String? = null, prices: String? = null) =
        PriceRule(subjectType = PriceBook.SUBJECT_USER, subjectId = "7", model = official.model, mode = mode, discount = discount, prices = prices)

    @Test fun officialDiscountIsOfficialTimesDiscountAndIgnoresGroupRatio() {
        val q = PriceBook.quote(official, vip, rule(PriceBook.MODE_OFFICIAL_DISCOUNT, discount = "0.7"), PriceBook.SOURCE_USER, markup = "1.2")
        val eff = PriceBook.effective(q)
        assertEquals("0.21", eff.inputPrice)
        assertEquals("1.75", eff.outputPrice)
        assertEquals(7000L, PriceBook.discountBp(q), "官方价 7 折，不乘 VIP 的 0.9，也不乘加价率")
    }

    @Test fun fixedPriceIsTakenAsIs() {
        val q = PriceBook.quote(official, vip, rule(PriceBook.MODE_FIXED, prices = """{"input":"0.2","output":"2"}"""), PriceBook.SOURCE_GROUP, markup = "1.2")
        val eff = PriceBook.effective(q)
        assertEquals("0.2", eff.inputPrice)
        assertEquals("2", eff.outputPrice)
        assertEquals("1", q.ratio)
        assertEquals(8000L, PriceBook.discountBp(q))
    }

    @Test fun platformRuleIsPlatformPriceTimesGroupRatio() {
        val q = PriceBook.quote(official, vip, rule(PriceBook.MODE_PLATFORM), PriceBook.SOURCE_USER, markup = "1.2")
        val eff = PriceBook.effective(q)
        // 平台价 = 2.5 × 1.2 = 3；× 0.9 = 2.7
        assertEquals("2.7", eff.outputPrice)
        assertEquals(10800L, PriceBook.discountBp(q))
    }

    @Test fun fixedPriceIsRejectedForTieredModels() {
        val tiered = official.copy(tiers = """[{"maxPromptTokens":200000,"input":"0.3","output":"2.5"},{"maxPromptTokens":null,"input":"0.6","output":"5"}]""")
        assertFailsWith<IllegalArgumentException> {
            PriceBook.quote(tiered, vip, rule(PriceBook.MODE_FIXED, prices = """{"input":"0.2","output":"2"}"""), PriceBook.SOURCE_USER, "1")
        }
    }

    @Test fun officialDiscountAppliesToEveryTier() {
        val tiered = official.copy(tiers = """[{"maxPromptTokens":200000,"input":"0.3","output":"2.5"},{"maxPromptTokens":null,"input":"0.6","output":"5"}]""")
        val q = PriceBook.quote(tiered, vip, rule(PriceBook.MODE_OFFICIAL_DISCOUNT, discount = "0.5"), PriceBook.SOURCE_USER, "1.2")
        // 售价轨就是官方价（含分档），倍率 = 折扣：高档同样是官方价的一半
        assertEquals(tiered.tiers, q.sale.tiers)
        assertEquals("0.5", q.ratio)
    }
}
