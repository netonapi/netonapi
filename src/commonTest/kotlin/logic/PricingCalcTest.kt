package logic

import kotlin.test.Test
import kotlin.test.assertEquals

class PricingCalcTest {
    @Test
    fun parseMicroDecimalStrings() {
        assertEquals(3_500_000L, PricingCalc.parseMicro("3.5"))
        assertEquals(0L, PricingCalc.parseMicro("0"))
        assertEquals(2_500_000L, PricingCalc.parseMicro("2.5"))
        assertEquals(150_000L, PricingCalc.parseMicro("0.15"))
        assertEquals(1L, PricingCalc.parseMicro("0.000001"))
    }

    @Test
    fun parseRatioTenThousandths() {
        assertEquals(10000, PricingCalc.parseRatio("1.0"))
        assertEquals(8000, PricingCalc.parseRatio("0.8"))
        assertEquals(6000, PricingCalc.parseRatio("0.6"))
    }

    @Test
    fun tokenCostRoundsUp() {
        // 2.5 USD/1M → 1000 tokens = 2500 μUSD
        assertEquals(2500L, PricingCalc.tokenCost(1000, PricingCalc.parseMicro("2.5")))
        // 1 token 也要计 1μUSD（向上取整，不免费）
        assertEquals(3L, PricingCalc.tokenCost(1, PricingCalc.parseMicro("2.5")))
        assertEquals(0L, PricingCalc.tokenCost(0, PricingCalc.parseMicro("2.5")))
    }

    @Test
    fun chargeStandardVector() {
        val price = model.ModelPrice(model = "gpt-test", inputPrice = "2.5", outputPrice = "10.0")
        val usage = Usage(promptTokens = 1000, completionTokens = 500)
        // 2500 + 5000 = 7500 μUSD
        assertEquals(7500L, PricingCalc.charge(usage, price, groupRatio = "1.0"))
        assertEquals(6000L, PricingCalc.charge(usage, price, groupRatio = "0.8"))
    }

    @Test
    fun costUsesChannelDiscount() {
        val price = model.ModelPrice(model = "gpt-test", inputPrice = "2.5", outputPrice = "10.0")
        val usage = Usage(promptTokens = 1000, completionTokens = 500)
        assertEquals(4500L, PricingCalc.cost(usage, price, costDiscount = "0.6"))
    }

    @Test
    fun cacheTokensPricedSeparately() {
        val price = model.ModelPrice(
            model = "m", inputPrice = "2.0", outputPrice = "8.0",
            cacheReadPrice = "0.5",
        )
        // 缓存读 1000 tokens 按 cacheReadPrice；非缓存输入 = prompt - cacheRead
        val usage = Usage(promptTokens = 2000, completionTokens = 0, cacheReadTokens = 1000)
        // (2000-1000)*2.0 + 1000*0.5 = 2000 + 500 = 2500 μUSD
        assertEquals(2500L, PricingCalc.charge(usage, price, "1.0"))
    }

    @Test
    fun perRequestPriceWins() {
        val price = model.ModelPrice(model = "dalle", inputPrice = "99", outputPrice = "99", perRequestPrice = 40_000)
        val usage = Usage(promptTokens = 12345, completionTokens = 678)
        assertEquals(40_000L, PricingCalc.charge(usage, price, "1.0"))
        assertEquals(24_000L, PricingCalc.cost(usage, price, "0.6"))
    }
}
