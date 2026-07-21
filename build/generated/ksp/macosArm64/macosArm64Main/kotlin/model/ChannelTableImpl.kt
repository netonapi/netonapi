// AUTO-GENERATED - DO NOT EDIT
// 用户已手写 ChannelTable Facade，此处仅生成内部实现
package model

import neton.database.api.Table
import neton.database.adapter.sqlx.SqlxTableAdapter

internal object ChannelTableImpl : Table<Channel, Long> by SqlxTableAdapter<Channel, Long>(
    meta = ChannelMeta,
    mapper = ChannelRowMapper,
    toParams = { it -> mapOf(
            "id" to it.id,
            "name" to it.name,
            "type" to it.type,
            "base_url" to it.baseUrl,
            "groups" to it.groups,
            "models" to it.models,
            "model_mapping" to it.modelMapping,
            "param_override" to it.paramOverride,
            "priority" to it.priority,
            "weight" to it.weight,
            "status" to it.status,
            "ttfb_timeout_ms" to it.ttfbTimeoutMs,
            "idle_timeout_ms" to it.idleTimeoutMs,
            "proxy_url" to it.proxyUrl,
            "cost_discount" to it.costDiscount,
            "remark" to it.remark,
            "deleted" to it.deleted,
            "created_at" to it.createdAt,
            "updated_at" to it.updatedAt
    )},
    softDeleteConfig = neton.database.api.SoftDeleteConfig(deletedColumn = "deleted", notDeletedValue = 0),
    autoFillConfig = neton.database.api.AutoFillConfig(createdAtColumn = "created_at", updatedAtColumn = "updated_at"),
    
)