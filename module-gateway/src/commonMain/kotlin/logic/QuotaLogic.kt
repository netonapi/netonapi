package logic

import dto.PageResponse
import model.ConsumptionAccount
import model.QuotaTransaction

@neton.core.annotations.Logic(logger = "logic.gateway-quota")
class QuotaLogic(private val balances: ConsumptionBalanceLogic) {

    suspend fun account(userId: Long): ConsumptionAccount = balances.account(userId)

    suspend fun reserveInTx(userId: Long, amount: Long): Boolean = balances.reserveInTx(userId, amount)

    suspend fun lockInTx(userId: Long) = balances.lockInTx(userId)

    suspend fun releaseInTx(userId: Long, amount: Long) = balances.releaseInTx(userId, amount)

    suspend fun grant(userId: Long, amount: Long, type: String = "grant", ref: String? = null): Long {
        // 这里原本是 require(amount > 0)。IllegalArgumentException 会被 BufferedHttpDispatcher
        // 兜底成 500 + "Internal Server Error" —— 运营在后台把金额填成 0 或负数，看到的是一片空白。
        // 改为字段级 400 + 机器码，与 RedemptionCodeLogic 同处置。
        if (validateGrant(amount) != null) rejectField("amount", GatewayErrorCodes.QUOTA_GRANT_AMOUNT_INVALID)
        return applyDelta(userId, amount, type, ref)
    }

    /**
     * 撤回已发放的额度：运营纠错用（误发、活动档位填错、客诉复核后判定不成立）。
     *
     * 为什么**无条件扣、允许转负**（与 [settle] 同语义），而不是余额不足即拒（consume 的语义）：
     * 拒绝撤回会让这个功能在最需要它的场景下失效 —— 误发的额度只要被用户赶紧花掉就撤不回来了。
     * 那时运营手里剩下的唯一工具是直接改库，而改库会绕过台账：[model.QuotaTransaction] 是只增
     * 不改的审计链，balance_after 逐行累加，改掉一行会让它之后所有行的 balance_after 全部失真，
     * 等于把「钱的唯一审计来源」弄坏。转负的余额是事实的忠实记录：这笔钱用户欠着，下次充值先填坑。
     *
     * 只动 balance，**绝不动 reserved_balance** —— 后者由 [SettlementLogic] 独占管理
     * （reserve 占位、finalize 与 release 归还），两个写入方碰同一列就会互相覆盖。
     * 撤回到可用余额为负是正确结果：新请求的预留条件是 `balance - reserved_balance >= 预留额`，
     * 不满足自然被拒；在途请求的钱已经花在上游了，撤回拦不住它，也不该假装拦得住。
     *
     * [ref] 必填。撤回是负向操作，重复执行的后果是用户被多扣钱，比 grant 双发更严重
     * （那是平台损失，这是用户损失加客诉），所以幂等不给「可选」的余地 —— 由台账 ref 的唯一
     * 约束保证，与结算 / 充值 / 兑换同一套机制。建议传 `revoke:<工单号>`，既做幂等键又做审计线索。
     */
    suspend fun revoke(userId: Long, amount: Long, ref: String?): Long {
        when (validateRevoke(amount, ref)) {
            GatewayErrorCodes.QUOTA_REVOKE_AMOUNT_INVALID -> rejectField("amount", GatewayErrorCodes.QUOTA_REVOKE_AMOUNT_INVALID)
            GatewayErrorCodes.QUOTA_REVOKE_REF_REQUIRED -> rejectField("ref", GatewayErrorCodes.QUOTA_REVOKE_REF_REQUIRED)
        }
        return applyDelta(userId, -amount, TYPE_REVOKE, ref)
    }

    /**
     * 结算（后付费）：无条件扣减 charged，即使余额转负——保证 usage_log.charged 恒有对应台账扣款。
     * 与 consume() 的区别：consume 余额不足即拒（预付/校验场景）；settle 用于已服务请求的最终结算。
     */
    suspend fun settle(userId: Long, amount: Long, ref: String?): Long {
        require(amount >= 0) { "settle amount must be >= 0" }
        if (amount == 0L) return account(userId).balance
        return applyDelta(userId, -amount, "consume", ref)
    }

    suspend fun applyDelta(userId: Long, delta: Long, type: String, ref: String?): Long =
        balances.applyDelta(userId, delta, type, ref)

    suspend fun debitInTx(userId: Long, delta: Long, type: String, ref: String?): Long =
        balances.applyDeltaInTx(userId, delta, type, ref)

    suspend fun transactions(userId: Long): List<QuotaTransaction> =
        balances.transactions(userId).map(QuotaTransaction::from)

    suspend fun transactionPage(userId: Long, page: Int, size: Int): PageResponse<QuotaTransaction> {
        val result = balances.transactionPage(userId, page, size)
        return PageResponse(result.list.map(QuotaTransaction::from), result.total, page, size, result.totalPages)
    }

    companion object {
        /**
         * 台账 type：运营撤回。与 grant/recharge/redeem/invite/consume 并列（六种是穷举核实过的
         * 全部产出点，`refund` 至今无人写入），清单与各自出处见 [model.QuotaTransaction]。
         */
        const val TYPE_REVOKE = "revoke"

        // ── 下面三个 balanceAfter* 都**没有生产调用方**：它们是把账务约定写成可执行形式的文档 ──
        // 真实的余额变动全在 debitInTx 里由 SQL `balance = balance + delta` 完成，那必须是数据库内的
        // 单条原子自增（行级锁串行化并发扣减）。在 Kotlin 侧先算出新值再写回会重新引入丢更新竞态：
        // 并发两笔各自读到同一个旧 balance，后写的把先写的盖掉，钱就少了。所以这三个函数
        // **原则上就不能**被生产路径用上，别去「顺手接上」。
        // 代价必须说清楚：断言它们**不等于**断言行为。实测往 revoke 里注入「余额不足即拒」之后，
        // QuotaLogicTest 七条全绿、模块 BUILD SUCCESSFUL；抓住它的是 harness S51 的块 C 与块 G。
        fun balanceAfterGrant(balance: Long, amount: Long): Long = balance + amount
        fun balanceAfterConsume(balance: Long, amount: Long): Long? =
            if (balance < amount) null else balance - amount

        /** 撤回后的余额：无条件扣，允许转负（理由见 [revoke]）。 */
        fun balanceAfterRevoke(balance: Long, amount: Long): Long = balance - amount

        /**
         * 入参校验，返回 [GatewayErrorCodes] 机器码，null 表示合法。
         *
         * 抽成纯函数是因为这两条是**安全性质**而不是风格偏好：amount 放行非正数的话，
         * `revoke(amount = -100)` 就是一次发放 —— 持有 gateway:quota:revoke 的人借此绕过
         * gateway:quota:grant，两个权限的分离形同虚设。ref 放行空值的话撤回就没有幂等键，
         * 运营在后台双击一次就是双扣，而且台账上看不出这是两次还是一次。
         */
        fun validateRevoke(amount: Long, ref: String?): String? = when {
            // 非正数的撤回等于发放，那需要 gateway:quota:grant
            amount <= 0 -> GatewayErrorCodes.QUOTA_REVOKE_AMOUNT_INVALID
            // 建议 revoke:<工单号>；没有幂等键，重复提交会重复扣款
            ref.isNullOrBlank() -> GatewayErrorCodes.QUOTA_REVOKE_REF_REQUIRED
            else -> null
        }

        /** 同 [validateRevoke] 的 amount 那一半：非正数的发放会把台账灌满 0 额行与反向行。 */
        fun validateGrant(amount: Long): String? =
            // 要撤回额度请用 revoke，它走独立的权限与台账类型
            if (amount <= 0) GatewayErrorCodes.QUOTA_GRANT_AMOUNT_INVALID else null
    }
}
