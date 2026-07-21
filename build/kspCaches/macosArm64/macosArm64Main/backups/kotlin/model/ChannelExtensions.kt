// AUTO-GENERATED - DO NOT EDIT
// 定型 API：UserTable.get(id) / UserTable.destroy(id) / UserTable.update(id){ } / UserTable.query{ }
// update(id){ } 为 mutate 风格：lambda 内直接赋值，copy 由框架内部生成
package model

import neton.database.api.Predicate
import neton.database.api.PredicateScope
import neton.database.api.Query
import table.ChannelTable

/** 用于 UserTable.update(id) { name = x; email = y } 的可变作用域，copy 在内部生成 */
class ChannelUpdateScope(initial: Channel) {
    var name: String
    var type: String
    var baseUrl: String
    var groups: String
    var models: String
    var modelMapping: String?
    var paramOverride: String?
    var priority: Int
    var weight: Int
    var status: Int
    var ttfbTimeoutMs: Long
    var idleTimeoutMs: Long
    var proxyUrl: String?
    var costDiscount: String
    var remark: String?
    var deleted: Int
    var createdAt: Long?
    var updatedAt: Long?
    init {
        name = initial.name
        type = initial.type
        baseUrl = initial.baseUrl
        groups = initial.groups
        models = initial.models
        modelMapping = initial.modelMapping
        paramOverride = initial.paramOverride
        priority = initial.priority
        weight = initial.weight
        status = initial.status
        ttfbTimeoutMs = initial.ttfbTimeoutMs
        idleTimeoutMs = initial.idleTimeoutMs
        proxyUrl = initial.proxyUrl
        costDiscount = initial.costDiscount
        remark = initial.remark
        deleted = initial.deleted
        createdAt = initial.createdAt
        updatedAt = initial.updatedAt
    }
}

// ---------- 表级 UserTable.update(id){ }（mutate 风格）----------
suspend fun ChannelTable.update(id: Long, block: ChannelUpdateScope.() -> kotlin.Unit): Channel? {
    val current = ChannelTable.get(id) ?: return null
    val scope = ChannelUpdateScope(current)
    scope.block()
    val updated = current.copy(name = scope.name, type = scope.type, baseUrl = scope.baseUrl, groups = scope.groups, models = scope.models, modelMapping = scope.modelMapping, paramOverride = scope.paramOverride, priority = scope.priority, weight = scope.weight, status = scope.status, ttfbTimeoutMs = scope.ttfbTimeoutMs, idleTimeoutMs = scope.idleTimeoutMs, proxyUrl = scope.proxyUrl, costDiscount = scope.costDiscount, remark = scope.remark, deleted = scope.deleted, createdAt = scope.createdAt, updatedAt = scope.updatedAt)
    ChannelTable.update(updated)
    return updated
}
