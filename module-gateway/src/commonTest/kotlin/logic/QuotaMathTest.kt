package logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 账务纯逻辑边界。事务/CAS/四表不变量/settle/cancellation 属 DB 集成测试（待入 CI，见 SPEC 待办）。
 */
class QuotaMathTest {
    @Test fun grantAddsPositive() {
        assertEquals(1300L, QuotaLogic.balanceAfterGrant(1000, 300))
    }
    @Test fun consumeMathRejectsInsufficient() {
        // balanceAfterConsume 仅供预付校验；结算走 debitInTx(-amount) 允许转负（此处不覆盖）
        assertNull(QuotaLogic.balanceAfterConsume(200, 300))
        assertEquals(700L, QuotaLogic.balanceAfterConsume(1000, 300))
        assertEquals(0L, QuotaLogic.balanceAfterConsume(300, 300))
    }
}
