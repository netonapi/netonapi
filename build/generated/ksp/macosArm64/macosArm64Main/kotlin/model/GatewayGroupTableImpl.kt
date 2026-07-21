// AUTO-GENERATED - DO NOT EDIT
// 用户已手写 GatewayGroupTable Facade，此处仅生成内部实现
package model

import neton.database.api.Table
import neton.database.adapter.sqlx.SqlxTableAdapter

internal object GatewayGroupTableImpl : Table<GatewayGroup, Long> by SqlxTableAdapter<GatewayGroup, Long>(
    meta = GatewayGroupMeta,
    mapper = GatewayGroupRowMapper,
    toParams = { it -> mapOf(
            "id" to it.id,
            "name" to it.name,
            "ratio" to it.ratio,
            "description" to it.description,
            "deleted" to it.deleted,
            "created_at" to it.createdAt,
            "updated_at" to it.updatedAt
    )},
    softDeleteConfig = neton.database.api.SoftDeleteConfig(deletedColumn = "deleted", notDeletedValue = 0),
    autoFillConfig = neton.database.api.AutoFillConfig(createdAtColumn = "created_at", updatedAtColumn = "updated_at"),
    
)