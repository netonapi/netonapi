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
        val acc = account(userId)
        val newBalance = balanceAfterGrant(acc.balance, amount)
        QuotaAccountTable.query {
            where { and(QuotaAccount::id eq acc.id, QuotaAccount::version eq acc.version) }
        }.update {
            set(QuotaAccount::balance, newBalance)
            set(QuotaAccount::version, acc.version + 1)
        }
        QuotaTransactionTable.insert(
            QuotaTransaction(userId = userId, type = type, amount = amount, balanceAfter = newBalance, ref = ref)
        )
        log.info("quota grant user=$userId amount=$amount balance=$newBalance type=$type")
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
