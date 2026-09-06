package logic

import model.QuotaAccount
import model.QuotaTransaction
import neton.core.http.HttpException
import neton.core.http.NetonErrorCode
import neton.database.dsl.*
import neton.logging.Logger
import table.QuotaAccountTable
import table.QuotaTransactionTable

@neton.core.annotations.Logic(logger = "logic.gateway-quota")
class QuotaLogic(private val log: Logger) {

    suspend fun account(userId: Long): QuotaAccount {
        QuotaAccountTable.oneWhere { QuotaAccount::userId eq userId }?.let { return it }
        // 并发首建：两个并发首请求都 miss 会各自 INSERT，撞唯一索引 uk_gateway_quota_accounts_user 抛错、
        // 在 bill() 事务内回滚导致已服务请求丢账。ON CONFLICT DO NOTHING 幂等建账（balance/version 走列默认 0），
        // 再重读（本次或并发对方插入的都可见）。raw SQL 为 PostgreSQL 专用，MySQL 待适配（INSERT IGNORE / ON DUPLICATE KEY）。
        neton.database.dbContext().execute(
            "INSERT INTO gateway_quota_accounts (user_id) VALUES (:uid) ON CONFLICT (user_id) DO NOTHING",
            mapOf("uid" to userId),
        )
        return QuotaAccountTable.oneWhere { QuotaAccount::userId eq userId }
            ?: error("account create-or-read failed userId=$userId")
    }

    suspend fun grant(userId: Long, amount: Long, type: String = "grant", ref: String? = null): Long {
        // 这里原本是 require(amount > 0)。IllegalArgumentException 会被 BufferedHttpDispatcher
        // 兜底成 500 + "Internal Server Error"，message 不进信封 —— 运营在后台把金额填成 0 或
        // 负数，看到的是一片空白，而原因其实是「金额必须是正数」。与 RedemptionCodeLogic 同处置。
        validateGrant(amount)?.let { throw HttpException(NetonErrorCode.INVALID_PARAMS, it) }
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
        validateRevoke(amount, ref)?.let { throw HttpException(NetonErrorCode.INVALID_PARAMS, it) }
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

    /**
     * 原子应用余额增量：余额自增 + 台账插入同一事务（全或无）。delta 可正可负，允许转负。
     * 自增在 SQL 内计算（balance = balance + delta），行级锁串行化并发写，无 version 竞争、无重试。
     */
    suspend fun applyDelta(userId: Long, delta: Long, type: String, ref: String?): Long =
        neton.database.dbContext().transaction { debitInTx(userId, delta, type, ref) }

    /**
     * 事务内原子扣减：balance = balance + delta 在 SQL 内自增（行级锁串行化并发扣减，无 version 竞争），
     * 事务内回读得准确 balanceAfter，插台账。供 bill() 与 usage_log/token 同事务提交（账务全或无）。
     */
    suspend fun debitInTx(userId: Long, delta: Long, type: String, ref: String?): Long {
        val acc = account(userId)
        // 结算幂等：ref 非空时先插台账哨兵（ref 唯一约束）。重复 ref（重试/outbox 重放）插入 0 行 → 已结算，
        // 跳过余额自增，保证同一 usage 只扣一次。
        if (ref != null) {
            val ins = neton.database.dbContext().execute(
                "INSERT INTO gateway_quota_transactions (user_id, type, amount, balance_after, ref, created_at) " +
                    "VALUES (:uid, :t, :amt, 0, :ref, :ts) ON CONFLICT (ref) WHERE ref IS NOT NULL DO NOTHING",
                // created_at 必须在这里就填真值。哨兵行不是临时占位后就删掉的东西 —— 它就是最终的
                // 台账行，后面的 UPDATE 只回填 balance_after。写成字面量 0 的话，**每一条带 ref 的
                // 台账**（结算 settlement:*、充值 gateway:quota:*、兑换 redeem:*）时间戳都停在 epoch 0，
                // 而 idx_gateway_quota_tx_user(user_id, created_at) 正是为按时间查流水建的：全表同值
                // 等于索引白建，「这个客户上个月花了多少」在台账上无法回答。台账是钱的唯一审计来源，
                // 走非 ref 路径的行时间反而是对的，两种行混在一张表里按时间排序会看不出真实先后。
                mapOf("uid" to userId, "t" to type, "amt" to delta, "ref" to ref, "ts" to nowMillis()),
            )
            if (ins == 0L) {
                log.warn("quota debit idempotent skip: ref=$ref already settled userId=$userId")
                return acc.balance
            }
        }
        checkedAdd(acc.balance, delta)  // 溢出保护：转账前校验 BIGINT 不溢出
        val affected = neton.database.dbContext().execute(
            "UPDATE gateway_quota_accounts SET balance = balance + :delta, version = version + 1 WHERE id = :id",
            mapOf("delta" to delta, "id" to acc.id),
        )
        check(affected == 1L) { "debit affected $affected rows (expected 1) userId=$userId accountId=${acc.id}" }
        val newBalance = account(userId).balance  // 事务内回读：本 tx 持行锁，读到本次自增结果
        if (ref != null) {
            neton.database.dbContext().execute(
                "UPDATE gateway_quota_transactions SET balance_after = :b WHERE ref = :ref",
                mapOf("b" to newBalance, "ref" to ref),
            )
        } else {
            QuotaTransactionTable.insert(
                QuotaTransaction(userId = userId, type = type, amount = delta, balanceAfter = newBalance, ref = null)
            )
        }
        return newBalance
    }

    private fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    /** BIGINT 溢出保护：同号相加结果变号即溢出。 */
    private fun checkedAdd(a: Long, b: Long): Long {
        val r = a + b
        check((a xor r) and (b xor r) >= 0L) { "quota balance overflow: $a + $b" }
        return r
    }


    suspend fun transactions(userId: Long): List<QuotaTransaction> =
        QuotaTransactionTable.query { where { QuotaTransaction::userId eq userId } }.list()

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
         * 入参校验，返回错误信息，null 表示合法。
         *
         * 抽成纯函数是因为这两条是**安全性质**而不是风格偏好：amount 放行非正数的话，
         * `revoke(amount = -100)` 就是一次发放 —— 持有 gateway:quota:revoke 的人借此绕过
         * gateway:quota:grant，两个权限的分离形同虚设。ref 放行空值的话撤回就没有幂等键，
         * 运营在后台双击一次就是双扣，而且台账上看不出这是两次还是一次。
         */
        fun validateRevoke(amount: Long, ref: String?): String? = when {
            amount <= 0 -> "撤回金额必须是正数；非正数的撤回等于发放，那需要 gateway:quota:grant"
            ref.isNullOrBlank() -> "撤回必须带 ref 做幂等键（建议 revoke:<工单号>），否则重复提交会重复扣款"
            else -> null
        }

        /** 同 [validateRevoke] 的 amount 那一半：非正数的发放会把台账灌满 0 额行与反向行。 */
        fun validateGrant(amount: Long): String? =
            if (amount <= 0) "发放金额必须是正数；要撤回额度请用 revoke，它走独立的权限与台账类型" else null
    }
}
