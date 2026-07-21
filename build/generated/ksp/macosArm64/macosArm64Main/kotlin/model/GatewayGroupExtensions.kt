// AUTO-GENERATED - DO NOT EDIT
// 定型 API：UserTable.get(id) / UserTable.destroy(id) / UserTable.update(id){ } / UserTable.query{ }
// update(id){ } 为 mutate 风格：lambda 内直接赋值，copy 由框架内部生成
package model

import neton.database.api.Predicate
import neton.database.api.PredicateScope
import neton.database.api.Query
import table.GatewayGroupTable

/** 用于 UserTable.update(id) { name = x; email = y } 的可变作用域，copy 在内部生成 */
class GatewayGroupUpdateScope(initial: GatewayGroup) {
    var name: String
    var ratio: String
    var description: String?
    var deleted: Int
    var createdAt: Long?
    var updatedAt: Long?
    init {
        name = initial.name
        ratio = initial.ratio
        description = initial.description
        deleted = initial.deleted
        createdAt = initial.createdAt
        updatedAt = initial.updatedAt
    }
}

// ---------- 表级 UserTable.update(id){ }（mutate 风格）----------
suspend fun GatewayGroupTable.update(id: Long, block: GatewayGroupUpdateScope.() -> kotlin.Unit): GatewayGroup? {
    val current = GatewayGroupTable.get(id) ?: return null
    val scope = GatewayGroupUpdateScope(current)
    scope.block()
    val updated = current.copy(name = scope.name, ratio = scope.ratio, description = scope.description, deleted = scope.deleted, createdAt = scope.createdAt, updatedAt = scope.updatedAt)
    GatewayGroupTable.update(updated)
    return updated
}
