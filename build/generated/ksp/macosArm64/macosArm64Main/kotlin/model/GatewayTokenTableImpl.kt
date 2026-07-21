// AUTO-GENERATED - DO NOT EDIT
// 用户已手写 GatewayTokenTable Facade，此处仅生成内部实现
package model

import neton.database.api.Table
import neton.database.adapter.sqlx.SqlxTableAdapter

internal object GatewayTokenTableImpl : Table<GatewayToken, Long> by SqlxTableAdapter<GatewayToken, Long>(
    meta = GatewayTokenMeta,
    mapper = GatewayTokenRowMapper,
    toParams = { it -> mapOf(
            "id" to it.id,
            "user_id" to it.userId,
            "name" to it.name,
            "key_hash" to it.keyHash,
            "key_display" to it.keyDisplay,
            "quota_budget" to it.quotaBudget,
            "quota_used" to it.quotaUsed,
            "expires_at" to it.expiresAt,
            "allowed_models" to it.allowedModels,
            "allowed_ips" to it.allowedIps,
            "group_override" to it.groupOverride,
            "status" to it.status,
            "deleted" to it.deleted,
            "created_at" to it.createdAt,
            "updated_at" to it.updatedAt
    )},
    softDeleteConfig = neton.database.api.SoftDeleteConfig(deletedColumn = "deleted", notDeletedValue = 0),
    autoFillConfig = neton.database.api.AutoFillConfig(createdAtColumn = "created_at", updatedAtColumn = "updated_at"),
    
)