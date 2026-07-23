package logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 账务纯逻辑不变量（DB 事务/并发另见集成测试待办）。 */
class QuotaMathTest {
    @Test fun settleAllowsNegative() {
        // settle 用 applyDelta(-amount)：余额不足也扣成负（后付费结算，保证 log.charged 恒有台账）
        assertEquals(700L, QuotaLogic.balanceAfterGrant(1000, -300))  // grant 数学同 +delta
        // consume（预付校验）余额不足返回 null
        assertNull(QuotaLogic.balanceAfterConsume(200, 300))
        assertEquals(0L, QuotaLogic.balanceAfterConsume(300, 300))
    }
    @Test fun grantAccumulates() {
        assertEquals(1300L, QuotaLogic.balanceAfterGrant(1000, 300))
    }
}
