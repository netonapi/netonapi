package logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BillingExprParserTest {

    @Test
    fun singleTier() {
        val p = BillingExprParser.parse("""tier("standard", p * 2.5 + cr * 1.25 + c * 10)""").getOrThrow()
        assertEquals("2.5", p.base.input)
        assertEquals("10", p.base.output)
        assertEquals("1.25", p.base.cacheRead)
        assertEquals("0", p.base.cacheWrite)
        assertNull(p.tiers)
    }

    @Test
    fun twoTiersByLength() {
        val p = BillingExprParser.parse(
            """len <= 200000 ? tier("0_200k", p * 1.25 + cr * 0.125 + c * 10) : tier("200k_plus", p * 2.5 + cr * 0.25 + c * 15)""",
        ).getOrThrow()
        val tiers = p.tiers!!
        assertEquals(2, tiers.size)
        assertEquals(200000L, tiers[0].maxPromptTokens)
        assertNull(tiers[1].maxPromptTokens)
        assertEquals("15", tiers[1].output)
        assertEquals("1.25", p.base.input, "基础档 = 第一档")
    }

    @Test
    fun nestedThreeTiers() {
        val p = BillingExprParser.parse(
            """len <= 100 ? tier("a", p * 1 + c * 2) : (len <= 200 ? tier("b", p * 3 + c * 4) : tier("c", p * 5 + c * 6))""",
        ).getOrThrow()
        assertEquals(listOf(100L, 200L, null), p.tiers!!.map { it.maxPromptTokens })
        assertEquals("5", p.tiers!![2].input)
    }

    @Test
    fun cc1hIgnoredWithNote() {
        val p = BillingExprParser.parse("""tier("standard", p * 3 + cr * 0.3 + cc * 3.75 + cc1h * 6 + c * 15)""").getOrThrow()
        assertEquals("3.75", p.base.cacheWrite)
        assertTrue(p.notes.contains("cc1h ignored"))
    }

    @Test
    fun unknownShapeFails() {
        assertTrue(BillingExprParser.parse("p * 2 ^ 2").isFailure)
        assertTrue(BillingExprParser.parse("""tier("x", max(p, 1))""").isFailure)
        assertTrue(BillingExprParser.parse("""tier("x", q * 1)""").isFailure)
    }
}
