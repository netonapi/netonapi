// AUTO-GENERATED - DO NOT EDIT
// 定型 API：UserTable.get(id) / UserTable.destroy(id) / UserTable.update(id){ } / UserTable.query{ }
// update(id){ } 为 mutate 风格：lambda 内直接赋值，copy 由框架内部生成
package model

import neton.database.api.Predicate
import neton.database.api.PredicateScope
import neton.database.api.Query
import table.QuotaTransactionTable

/** 用于 UserTable.update(id) { name = x; email = y } 的可变作用域，copy 在内部生成 */
class QuotaTransactionUpdateScope(initial: QuotaTransaction) {
    var userId: Long
    var type: String
    var amount: Long
    var balanceAfter: Long
    var ref: String?
    var createdAt: Long?
    init {
        userId = initial.userId
        type = initial.type
        amount = initial.amount
        balanceAfter = initial.balanceAfter
        ref = initial.ref
        createdAt = initial.createdAt
    }
}

// ---------- 表级 UserTable.update(id){ }（mutate 风格）----------
suspend fun QuotaTransactionTable.update(id: Long, block: QuotaTransactionUpdateScope.() -> kotlin.Unit): QuotaTransaction? {
    val current = QuotaTransactionTable.get(id) ?: return null
    val scope = QuotaTransactionUpdateScope(current)
    scope.block()
    val updated = current.copy(userId = scope.userId, type = scope.type, amount = scope.amount, balanceAfter = scope.balanceAfter, ref = scope.ref, createdAt = scope.createdAt)
    QuotaTransactionTable.update(updated)
    return updated
}
