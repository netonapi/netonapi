// AUTO-GENERATED - DO NOT EDIT
// 定型 API：UserTable.get(id) / UserTable.destroy(id) / UserTable.update(id){ } / UserTable.query{ }
// update(id){ } 为 mutate 风格：lambda 内直接赋值，copy 由框架内部生成
package model

import neton.database.api.Predicate
import neton.database.api.PredicateScope
import neton.database.api.Query
import table.QuotaAccountTable

/** 用于 UserTable.update(id) { name = x; email = y } 的可变作用域，copy 在内部生成 */
class QuotaAccountUpdateScope(initial: QuotaAccount) {
    var userId: Long
    var balance: Long
    var version: Long
    var createdAt: Long?
    var updatedAt: Long?
    init {
        userId = initial.userId
        balance = initial.balance
        version = initial.version
        createdAt = initial.createdAt
        updatedAt = initial.updatedAt
    }
}

// ---------- 表级 UserTable.update(id){ }（mutate 风格）----------
suspend fun QuotaAccountTable.update(id: Long, block: QuotaAccountUpdateScope.() -> kotlin.Unit): QuotaAccount? {
    val current = QuotaAccountTable.get(id) ?: return null
    val scope = QuotaAccountUpdateScope(current)
    scope.block()
    val updated = current.copy(userId = scope.userId, balance = scope.balance, version = scope.version, createdAt = scope.createdAt, updatedAt = scope.updatedAt)
    QuotaAccountTable.update(updated)
    return updated
}
