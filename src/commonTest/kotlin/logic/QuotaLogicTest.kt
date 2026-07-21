package logic

import kotlin.test.Test
import kotlin.test.assertEquals

class QuotaLogicTest {
    @Test
    fun consumeMathNeverGoesNegative() {
        assertEquals(700L, QuotaLogic.balanceAfterConsume(balance = 1000, amount = 300))
        assertEquals(null, QuotaLogic.balanceAfterConsume(balance = 200, amount = 300)) // 不足
        assertEquals(0L, QuotaLogic.balanceAfterConsume(balance = 300, amount = 300))
    }

    @Test
    fun grantAccumulates() {
        assertEquals(1300L, QuotaLogic.balanceAfterGrant(balance = 1000, amount = 300))
    }
}
