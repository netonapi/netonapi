// AUTO-GENERATED - DO NOT EDIT
// 定型 API：UserTable.get(id) / UserTable.destroy(id) / UserTable.update(id){ } / UserTable.query{ }
// update(id){ } 为 mutate 风格：lambda 内直接赋值，copy 由框架内部生成
package model

import neton.database.api.Predicate
import neton.database.api.PredicateScope
import neton.database.api.Query
import table.UsageLogTable

/** 用于 UserTable.update(id) { name = x; email = y } 的可变作用域，copy 在内部生成 */
class UsageLogUpdateScope(initial: UsageLog) {
    var userId: Long
    var tokenId: Long
    var channelId: Long
    var requestModel: String
    var upstreamModel: String
    var promptTokens: Int
    var completionTokens: Int
    var cacheReadTokens: Int
    var cacheWriteTokens: Int
    var charged: Long
    var cost: Long
    var ttfbMs: Long?
    var latencyMs: Long?
    var stream: Int
    var status: String
    var errorCode: String?
    var createdAt: Long?
    init {
        userId = initial.userId
        tokenId = initial.tokenId
        channelId = initial.channelId
        requestModel = initial.requestModel
        upstreamModel = initial.upstreamModel
        promptTokens = initial.promptTokens
        completionTokens = initial.completionTokens
        cacheReadTokens = initial.cacheReadTokens
        cacheWriteTokens = initial.cacheWriteTokens
        charged = initial.charged
        cost = initial.cost
        ttfbMs = initial.ttfbMs
        latencyMs = initial.latencyMs
        stream = initial.stream
        status = initial.status
        errorCode = initial.errorCode
        createdAt = initial.createdAt
    }
}

// ---------- 表级 UserTable.update(id){ }（mutate 风格）----------
suspend fun UsageLogTable.update(id: Long, block: UsageLogUpdateScope.() -> kotlin.Unit): UsageLog? {
    val current = UsageLogTable.get(id) ?: return null
    val scope = UsageLogUpdateScope(current)
    scope.block()
    val updated = current.copy(userId = scope.userId, tokenId = scope.tokenId, channelId = scope.channelId, requestModel = scope.requestModel, upstreamModel = scope.upstreamModel, promptTokens = scope.promptTokens, completionTokens = scope.completionTokens, cacheReadTokens = scope.cacheReadTokens, cacheWriteTokens = scope.cacheWriteTokens, charged = scope.charged, cost = scope.cost, ttfbMs = scope.ttfbMs, latencyMs = scope.latencyMs, stream = scope.stream, status = scope.status, errorCode = scope.errorCode, createdAt = scope.createdAt)
    UsageLogTable.update(updated)
    return updated
}
