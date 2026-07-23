package logic

import model.QuotaAccount
import model.QuotaTransaction
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
        require(amount > 0) { "grant amount must be positive" }
        return applyDelta(userId, amount, type, ref)
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
        val affected = neton.database.dbContext().execute(
            "UPDATE gateway_quota_accounts SET balance = balance + :delta, version = version + 1 WHERE id = :id",
            mapOf("delta" to delta, "id" to acc.id),
        )
        check(affected == 1L) { "debit affected $affected rows (expected 1) userId=$userId accountId=${acc.id}" }
        val newBalance = account(userId).balance  // 事务内回读：本 tx 持行锁，读到本次自增结果
        QuotaTransactionTable.insert(
            QuotaTransaction(userId = userId, type = type, amount = delta, balanceAfter = newBalance, ref = ref)
        )
        return newBalance
    }


    suspend fun transactions(userId: Long): List<QuotaTransaction> =
        QuotaTransactionTable.query { where { QuotaTransaction::userId eq userId } }.list()

    companion object {
        fun balanceAfterGrant(balance: Long, amount: Long): Long = balance + amount
        fun balanceAfterConsume(balance: Long, amount: Long): Long? =
            if (balance < amount) null else balance - amount
    }
}
