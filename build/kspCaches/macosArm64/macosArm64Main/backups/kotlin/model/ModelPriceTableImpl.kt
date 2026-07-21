// AUTO-GENERATED - DO NOT EDIT
// 用户已手写 ModelPriceTable Facade，此处仅生成内部实现
package model

import neton.database.api.Table
import neton.database.adapter.sqlx.SqlxTableAdapter

internal object ModelPriceTableImpl : Table<ModelPrice, Long> by SqlxTableAdapter<ModelPrice, Long>(
    meta = ModelPriceMeta,
    mapper = ModelPriceRowMapper,
    toParams = { it -> mapOf(
            "id" to it.id,
            "model" to it.model,
            "input_price" to it.inputPrice,
            "output_price" to it.outputPrice,
            "cache_read_price" to it.cacheReadPrice,
            "cache_write_price" to it.cacheWritePrice,
            "per_request_price" to it.perRequestPrice,
            "sale_override" to it.saleOverride,
            "source" to it.source,
            "deleted" to it.deleted,
            "created_at" to it.createdAt,
            "updated_at" to it.updatedAt
    )},
    softDeleteConfig = neton.database.api.SoftDeleteConfig(deletedColumn = "deleted", notDeletedValue = 0),
    autoFillConfig = neton.database.api.AutoFillConfig(createdAtColumn = "created_at", updatedAtColumn = "updated_at"),
    
)