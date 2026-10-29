package logic

import model.ModelPrice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SalePricingTest {

    private val official = ModelPrice(model = "m", inputPrice = "2", outputPrice = "10", cacheReadPrice = "1", cacheWritePrice = "0")

    @Test
    fun followsTheSiteMarkupByDefault() {
        val sale = SalePricing.resolve(official, "1.5")
        assertEquals("3", sale.inputPrice)
        assertEquals("15", sale.outputPrice)
        assertEquals(SalePricing.Mode.FOLLOW, SalePricing.mode(official))
    }

    @Test
    fun modelMarkupBeatsTheSiteMarkup() {
        val p = official.copy(saleOverride = """{"markup":"1.2"}""")
        val sale = SalePricing.resolve(p, "1.5")
        assertEquals("2.4", sale.inputPrice)
        assertEquals("12", sale.outputPrice)
        assertEquals(SalePricing.Mode.MODEL_MARKUP, SalePricing.mode(p))
    }

    @Test
    fun fixedFieldsBeatTheMarkupOnlyForThoseFields() {
        val p = official.copy(saleOverride = """{"markup":"1.2","output":"11"}""")
        val sale = SalePricing.resolve(p, "1.5")
        assertEquals("2.4", sale.inputPrice)
        assertEquals("11", sale.outputPrice)
        assertEquals(SalePricing.Mode.CUSTOM, SalePricing.mode(p))
    }

    @Test
    fun marginGateUsesTheGivenPolicy() {
        // 加价率 0.9 → 售价低于官方价，最低毛利 1 时不许保存；最低毛利 0.8 时放行
        assertFails { SalePricing.requireMargin(official, markup = "0.9", floor = "1") }
        SalePricing.requireMargin(official, markup = "0.9", floor = "0.8")
    }

    @Test
    fun invalidModelMarkupIsRejected() {
        assertFails { SalePricing.resolve(official.copy(saleOverride = """{"markup":"0"}"""), "1") }
    }

    private val tiered = ModelPrice(
        model = "t", inputPrice = "1.25", outputPrice = "10",
        tiers = """[{"maxPromptTokens":200000,"input":"1.25","output":"10","cacheRead":"0","cacheWrite":"0"},
                    {"maxPromptTokens":null,"input":"2.5","output":"15","cacheRead":"0","cacheWrite":"0"}]""",
    )

    @Test
    fun markupAppliesToEveryTier() {
        val sale = SalePricing.resolve(tiered, "2")
        assertEquals("5", PricingTiers.select(sale, 300_000).inputPrice)
        assertEquals("30", PricingTiers.select(sale, 300_000).outputPrice)
        assertEquals("2.5", PricingTiers.select(sale, 100).inputPrice)
    }

    @Test
    fun fixedSalePriceOnlyChangesBaseTier() {
        val sale = SalePricing.resolve(tiered.copy(saleOverride = """{"input":"9"}"""), "2")
        assertEquals("9", sale.inputPrice)
        // 基础档（第一档）用固定售价；长上下文档不受固定售价影响，按加价率走
        assertEquals("9", PricingTiers.select(sale, 100).inputPrice)
        assertEquals("5", PricingTiers.select(sale, 300_000).inputPrice)
    }

    @Test
    fun reserveUsesMostExpensiveSaleTier() {
        assertEquals("5", PricingTiers.mostExpensive(SalePricing.resolve(tiered, "2")).inputPrice)
    }
}
