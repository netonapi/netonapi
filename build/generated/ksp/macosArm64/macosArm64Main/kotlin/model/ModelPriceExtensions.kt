// AUTO-GENERATED - DO NOT EDIT
// 定型 API：UserTable.get(id) / UserTable.destroy(id) / UserTable.update(id){ } / UserTable.query{ }
// update(id){ } 为 mutate 风格：lambda 内直接赋值，copy 由框架内部生成
package model

import neton.database.api.Predicate
import neton.database.api.PredicateScope
import neton.database.api.Query
import table.ModelPriceTable

/** 用于 UserTable.update(id) { name = x; email = y } 的可变作用域，copy 在内部生成 */
class ModelPriceUpdateScope(initial: ModelPrice) {
    var model: String
    var inputPrice: String
    var outputPrice: String
    var cacheReadPrice: String
    var cacheWritePrice: String
    var perRequestPrice: Long?
    var saleOverride: String?
    var source: String
    var deleted: Int
    var createdAt: Long?
    var updatedAt: Long?
    init {
        model = initial.model
        inputPrice = initial.inputPrice
        outputPrice = initial.outputPrice
        cacheReadPrice = initial.cacheReadPrice
        cacheWritePrice = initial.cacheWritePrice
        perRequestPrice = initial.perRequestPrice
        saleOverride = initial.saleOverride
        source = initial.source
        deleted = initial.deleted
        createdAt = initial.createdAt
        updatedAt = initial.updatedAt
    }
}

// ---------- 表级 UserTable.update(id){ }（mutate 风格）----------
suspend fun ModelPriceTable.update(id: Long, block: ModelPriceUpdateScope.() -> kotlin.Unit): ModelPrice? {
    val current = ModelPriceTable.get(id) ?: return null
    val scope = ModelPriceUpdateScope(current)
    scope.block()
    val updated = current.copy(model = scope.model, inputPrice = scope.inputPrice, outputPrice = scope.outputPrice, cacheReadPrice = scope.cacheReadPrice, cacheWritePrice = scope.cacheWritePrice, perRequestPrice = scope.perRequestPrice, saleOverride = scope.saleOverride, source = scope.source, deleted = scope.deleted, createdAt = scope.createdAt, updatedAt = scope.updatedAt)
    ModelPriceTable.update(updated)
    return updated
}
