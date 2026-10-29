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

    /**
     * 撤回与消费的**算术约定**分界线：同样余额不够，consume 的纯函数返回 null（拒绝）、
     * revoke 的纯函数返回负数（照扣）。
     *
     * ⚠️ 这条**不是行为守卫**。它原本写着「若哪天有人把 revoke 改成余额不足即拒，这两个断言会
     * 同时红」—— 那句话是假的，已被实测推翻：往 revoke 里注入
     * `if (account(userId).balance < amount) throw HttpException(...)` 之后，本文件 7 条全绿、
     * 整个模块 BUILD SUCCESSFUL。原因是 balanceAfterRevoke 没有生产调用方（见 QuotaLogic 里
     * 三个 balanceAfter* 上方的说明）。真正抓住这个变异的是 harness S51 的块 C（撤到转负必须
     * 200）与块 G（转负后 relay 必须 402 insufficient_quota），两条一起红 = 84/2。
     * **所以改 revoke 的余额判断必须跑 S51，跑单测不够。**
     *
     * 留着它仍有价值，价值是文档一致性：谁改了这两个纯函数之一的符号约定就会红。与 QuotaMathTest
     * 里「balanceAfterConsume 仅供预付校验；结算走 debitInTx(-amount) 允许转负（此处不覆盖）」
     * 是同一类诚实标注。
     *
     * 至于「撤回允许转负」本身为什么不能让步：那种看起来更安全的改法会让撤回在最需要它的场景下
     * 失效 —— 误发的额度只要被用户赶紧花掉就撤不回来了，运营剩下的唯一工具是直接改库，那会绕过
     * 只增不改的台账、让 balance_after 链从改动那行起全部失真。
     */
    @Test
    fun revokeGoesNegativeWhereConsumeRefuses() {
        assertEquals(null, QuotaLogic.balanceAfterConsume(balance = 200, amount = 300))
        assertEquals(-100L, QuotaLogic.balanceAfterRevoke(balance = 200, amount = 300))
        // 够扣的时候两者一致，区别只在不够扣的那一支。
        assertEquals(700L, QuotaLogic.balanceAfterConsume(balance = 1000, amount = 300))
        assertEquals(700L, QuotaLogic.balanceAfterRevoke(balance = 1000, amount = 300))
    }

    /**
     * 非正数的撤回必须被拒，而且理由要**点名 grant 权限**。
     *
     * 这不是输入卫生：`revoke(amount = -100)` 就是一次发放，持有 gateway:quota:revoke 的人
     * 借此绕过 gateway:quota:grant，两个权限的分离形同虚设。所以校验放在 logic 层而不是
     * controller 层 —— controller 只是众多调用方之一。
     */
    @Test
    fun revokeRejectsNonPositiveAmountAsGrantBypass() {
        for (bad in listOf(0L, -1L, -100000L)) {
            val code = QuotaLogic.validateRevoke(amount = bad, ref = "revoke:t1")
            assertEquals(GatewayErrorCodes.QUOTA_REVOKE_AMOUNT_INVALID, code, "amount=$bad 被放行了，等于开了一个绕过 grant 权限的发放口子")
        }
        assertEquals(null, QuotaLogic.validateRevoke(amount = 1, ref = "revoke:t1"))
    }

    /** ref 是撤回唯一的幂等键，不给「可选」的余地：空 ref 意味着运营双击就是双扣。 */
    @Test
    fun revokeRequiresRefForIdempotency() {
        assertEquals(GatewayErrorCodes.QUOTA_REVOKE_REF_REQUIRED, QuotaLogic.validateRevoke(amount = 100, ref = null))
        assertEquals(GatewayErrorCodes.QUOTA_REVOKE_REF_REQUIRED, QuotaLogic.validateRevoke(amount = 100, ref = ""))
        assertEquals(GatewayErrorCodes.QUOTA_REVOKE_REF_REQUIRED, QuotaLogic.validateRevoke(amount = 100, ref = "   "))
        assertEquals(null, QuotaLogic.validateRevoke(amount = 100, ref = "revoke:t1"))
    }

    /** 发放侧同一条：非正数放行会把台账灌满 0 额行与反向行。 */
    @Test
    fun grantRejectsNonPositiveAmount() {
        // 发放侧与撤回侧是两个机器码：前端文案据此把人指向 revoke，否则运营想不出该怎么减额度。
        assertEquals(GatewayErrorCodes.QUOTA_GRANT_AMOUNT_INVALID, QuotaLogic.validateGrant(amount = 0))
        assertEquals(GatewayErrorCodes.QUOTA_GRANT_AMOUNT_INVALID, QuotaLogic.validateGrant(amount = -1))
        assertEquals(null, QuotaLogic.validateGrant(amount = 1))
    }

    /** 台账类型串必须与 model 的 KDoc 一致：它是审计查询的过滤条件，改名等于让历史行查不到。 */
    @Test
    fun revokeLedgerTypeIsTheDocumentedString() {
        assertEquals("revoke", QuotaLogic.TYPE_REVOKE)
    }
}
