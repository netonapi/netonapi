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

class PricingValidationTest {
    @kotlin.test.Test
    fun rejectsMalformedDecimals() {
        for (bad in listOf("", "abc", "1.2.3", "--1", "1e5", " ", ".", "12345678")) {
            kotlin.test.assertFalse(PricingCalc.isValidDecimal(bad), "应拒绝: '$bad'")
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> { PricingCalc.parseMicro("abc") }
    }

    @kotlin.test.Test
    fun normalizesEquivalentForms() {
        kotlin.test.assertEquals("1.5", PricingCalc.normalize("01.5000"))
        kotlin.test.assertEquals("1", PricingCalc.normalize("1.0"))
        kotlin.test.assertEquals("0.5", PricingCalc.normalize(".5"))
        kotlin.test.assertEquals("0", PricingCalc.normalize("0.000"))
    }

    @kotlin.test.Test
    fun ratioRangeEnforced() {
        kotlin.test.assertEquals("0.8", PricingCalc.requireRatio("0.80"))
        kotlin.test.assertFailsWith<neton.core.http.ValidationException> { PricingCalc.requireRatio("0") }
        kotlin.test.assertFailsWith<neton.core.http.ValidationException> { PricingCalc.requireRatio("101") }
        kotlin.test.assertFailsWith<neton.core.http.ValidationException> { PricingCalc.requireRatio("-1") }
    }

    @kotlin.test.Test
    fun priceRejectsNegative() {
        val e = kotlin.test.assertFailsWith<neton.core.http.ValidationException> { PricingCalc.requirePrice("-2.5", "inputPrice") }
        kotlin.test.assertEquals(GatewayErrorCodes.PRICE_INVALID, e.message)
        kotlin.test.assertEquals(neton.core.http.ValidationError("inputPrice", GatewayErrorCodes.PRICE_INVALID, GatewayErrorCodes.PRICE_INVALID), e.errors.single())
        kotlin.test.assertEquals("2.5", PricingCalc.requirePrice("2.50"))
    }

    @kotlin.test.Test
    fun overflowIsLoudNotSilent() {
        // 7 位整数上限价 × 巨量 tokens → 必须抛而不是回绕
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            PricingCalc.tokenCost(Int.MAX_VALUE, PricingCalc.parseMicro("9999999"))
        }
    }

    @Test
    fun anthropicFreshInputIsNotReducedByCacheReads() {
        // Anthropic 的 input_tokens 只是新输入，缓存读另计：不能再从里面扣掉缓存读
        val price = model.ModelPrice(model = "c", inputPrice = "3", outputPrice = "15", cacheReadPrice = "0.3", cacheWritePrice = "3.75")
        val u = Usage(promptTokens = 1000, completionTokens = 0, cacheReadTokens = 2000, cacheWriteTokens = 0, promptIncludesCache = false)
        // 1000 × 3 + 2000 × 0.3 = 3000 + 600 μUSD
        kotlin.test.assertEquals(3600L, PricingCalc.charge(u, price, "1"))
        // OpenAI 口径（prompt 含缓存）不变：fresh = 3000 − 2000 = 1000
        val o = Usage(promptTokens = 3000, cacheReadTokens = 2000)
        kotlin.test.assertEquals(3600L, PricingCalc.charge(o, price, "1"))
    }
}
