// AUTO-GENERATED - DO NOT EDIT
// 定型 API：UserTable.get(id) / UserTable.destroy(id) / UserTable.update(id){ } / UserTable.query{ }
// update(id){ } 为 mutate 风格：lambda 内直接赋值，copy 由框架内部生成
package model

import neton.database.api.Predicate
import neton.database.api.PredicateScope
import neton.database.api.Query
import table.GatewayTokenTable

/** 用于 UserTable.update(id) { name = x; email = y } 的可变作用域，copy 在内部生成 */
class GatewayTokenUpdateScope(initial: GatewayToken) {
    var userId: Long
    var name: String
    var keyHash: String
    var keyDisplay: String
    var quotaBudget: Long?
    var quotaUsed: Long
    var expiresAt: Long?
    var allowedModels: String
    var allowedIps: String
    var groupOverride: String?
    var status: Int
    var deleted: Int
    var createdAt: Long?
    var updatedAt: Long?
    init {
        userId = initial.userId
        name = initial.name
        keyHash = initial.keyHash
        keyDisplay = initial.keyDisplay
        quotaBudget = initial.quotaBudget
        quotaUsed = initial.quotaUsed
        expiresAt = initial.expiresAt
        allowedModels = initial.allowedModels
        allowedIps = initial.allowedIps
        groupOverride = initial.groupOverride
        status = initial.status
        deleted = initial.deleted
        createdAt = initial.createdAt
        updatedAt = initial.updatedAt
    }
}

// ---------- 表级 UserTable.update(id){ }（mutate 风格）----------
suspend fun GatewayTokenTable.update(id: Long, block: GatewayTokenUpdateScope.() -> kotlin.Unit): GatewayToken? {
    val current = GatewayTokenTable.get(id) ?: return null
    val scope = GatewayTokenUpdateScope(current)
    scope.block()
    val updated = current.copy(userId = scope.userId, name = scope.name, keyHash = scope.keyHash, keyDisplay = scope.keyDisplay, quotaBudget = scope.quotaBudget, quotaUsed = scope.quotaUsed, expiresAt = scope.expiresAt, allowedModels = scope.allowedModels, allowedIps = scope.allowedIps, groupOverride = scope.groupOverride, status = scope.status, deleted = scope.deleted, createdAt = scope.createdAt, updatedAt = scope.updatedAt)
    GatewayTokenTable.update(updated)
    return updated
}
