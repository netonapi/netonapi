package logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 充值汇率（每 1 单位 pay_orders.price 发多少 μUSD）：没配 = 在线充值未开通（提示联系客户经理），
 * 不是 500，也不是按 1:1 发；写错了仍要响（运维错误，静默猜汇率比拒绝更糟）。
 */
class RechargeRateTest {

    @Test fun unsetMeansNotOpen() {
        assertNull(QuotaRechargeLogic.rateOf(null))
        assertNull(QuotaRechargeLogic.rateOf(""))
        assertNull(QuotaRechargeLogic.rateOf("   "))
    }

    @Test fun validRateIsParsed() {
        assertEquals(1389L, QuotaRechargeLogic.rateOf(" 1389 "))
    }

    @Test fun badRateIsAnOperatorError() {
        assertFailsWith<IllegalStateException> { QuotaRechargeLogic.rateOf("abc") }
        assertFailsWith<IllegalStateException> { QuotaRechargeLogic.rateOf("0") }
        assertFailsWith<IllegalStateException> { QuotaRechargeLogic.rateOf("-5") }
    }
}
