package logic

import model.QuotaAccount
import model.QuotaTransaction
import neton.database.dsl.*
import neton.logging.Logger
import table.QuotaAccountTable
import table.QuotaTransactionTable

@neton.core.annotations.Logic(logger = "logic.gateway-quota")
class QuotaLogic(private val log: Logger) {

    suspend fun account(userId: Long): QuotaAccount =
        QuotaAccountTable.oneWhere { QuotaAccount::userId eq userId }
            ?: QuotaAccountTable.insert(QuotaAccount(userId = userId))

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
     * 原子应用余额增量：每次尝试包一个事务（余额条件更新 + 台账插入同事务，全或无），
     * 冲突（version 变化）则整事务回滚、重读重试（新事务见已提交数据）。delta 可正可负，允许转负。
     */
    suspend fun applyDelta(userId: Long, delta: Long, type: String, ref: String?): Long {
        repeat(5) {
            val newBalance = neton.database.dbContext().transaction {
                debitInTx(userId, delta, type, ref)
            }
            if (newBalance != null) return newBalance
        }
        error("quota applyDelta contention user=$userId delta=$delta after 5 retries")
    }

    /**
     * 事务内条件扣减：读账户 → version 条件更新 → 插台账。冲突返回 null（调用方在事务外重试）。
     * 供 bill() 在同一事务内与 usage_log/token 一起提交（账务全或无）。
     */
    suspend fun debitInTx(userId: Long, delta: Long, type: String, ref: String?): Long? {
        val acc = account(userId)
        val newBalance = acc.balance + delta
        val updated = QuotaAccountTable.query {
            where { and(QuotaAccount::id eq acc.id, QuotaAccount::version eq acc.version) }
        }.update {
            set(QuotaAccount::balance, newBalance)
            set(QuotaAccount::version, acc.version + 1)
        }
        if (updated == 0L) return null  // 冲突：调用方回滚重试
        QuotaTransactionTable.insert(
            QuotaTransaction(userId = userId, type = type, amount = delta, balanceAfter = newBalance, ref = ref)
        )
        return newBalance
    }

    /**
     * 扣减：余额不足返回 false。并发安全：balance+version 条件更新，冲突重读重试（最多 3 次）。
     */
    suspend fun consume(userId: Long, amount: Long, ref: String?): Boolean {
        require(amount >= 0) { "consume amount must be >= 0" }
        if (amount == 0L) return true
        repeat(3) {
            val acc = account(userId)
            val newBalance = balanceAfterConsume(acc.balance, amount) ?: return false
            val updated = QuotaAccountTable.query {
                where { and(QuotaAccount::id eq acc.id, QuotaAccount::version eq acc.version) }
            }.update {
                set(QuotaAccount::balance, newBalance)
                set(QuotaAccount::version, acc.version + 1)
            }
            if (updated > 0) {
                QuotaTransactionTable.insert(
                    QuotaTransaction(userId = userId, type = "consume", amount = -amount, balanceAfter = newBalance, ref = ref)
                )
                return true
            }
        }
        log.warn("quota consume contention user=$userId amount=$amount")
        return false
    }

    suspend fun transactions(userId: Long): List<QuotaTransaction> =
        QuotaTransactionTable.query { where { QuotaTransaction::userId eq userId } }.list()

    companion object {
        fun balanceAfterGrant(balance: Long, amount: Long): Long = balance + amount
        fun balanceAfterConsume(balance: Long, amount: Long): Long? =
            if (balance < amount) null else balance - amount
    }
}
