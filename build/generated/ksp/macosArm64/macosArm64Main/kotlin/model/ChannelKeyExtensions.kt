// AUTO-GENERATED - DO NOT EDIT
// 定型 API：UserTable.get(id) / UserTable.destroy(id) / UserTable.update(id){ } / UserTable.query{ }
// update(id){ } 为 mutate 风格：lambda 内直接赋值，copy 由框架内部生成
package model

import neton.database.api.Predicate
import neton.database.api.PredicateScope
import neton.database.api.Query
import table.ChannelKeyTable

/** 用于 UserTable.update(id) { name = x; email = y } 的可变作用域，copy 在内部生成 */
class ChannelKeyUpdateScope(initial: ChannelKey) {
    var channelId: Long
    var apiKey: String
    var status: Int
    var failCount: Int
    var lastError: String?
    var lastUsedAt: Long?
    var deleted: Int
    var createdAt: Long?
    var updatedAt: Long?
    init {
        channelId = initial.channelId
        apiKey = initial.apiKey
        status = initial.status
        failCount = initial.failCount
        lastError = initial.lastError
        lastUsedAt = initial.lastUsedAt
        deleted = initial.deleted
        createdAt = initial.createdAt
        updatedAt = initial.updatedAt
    }
}

// ---------- 表级 UserTable.update(id){ }（mutate 风格）----------
suspend fun ChannelKeyTable.update(id: Long, block: ChannelKeyUpdateScope.() -> kotlin.Unit): ChannelKey? {
    val current = ChannelKeyTable.get(id) ?: return null
    val scope = ChannelKeyUpdateScope(current)
    scope.block()
    val updated = current.copy(channelId = scope.channelId, apiKey = scope.apiKey, status = scope.status, failCount = scope.failCount, lastError = scope.lastError, lastUsedAt = scope.lastUsedAt, deleted = scope.deleted, createdAt = scope.createdAt, updatedAt = scope.updatedAt)
    ChannelKeyTable.update(updated)
    return updated
}
