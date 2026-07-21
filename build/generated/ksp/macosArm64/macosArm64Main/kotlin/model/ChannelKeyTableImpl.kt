// AUTO-GENERATED - DO NOT EDIT
// 用户已手写 ChannelKeyTable Facade，此处仅生成内部实现
package model

import neton.database.api.Table
import neton.database.adapter.sqlx.SqlxTableAdapter

internal object ChannelKeyTableImpl : Table<ChannelKey, Long> by SqlxTableAdapter<ChannelKey, Long>(
    meta = ChannelKeyMeta,
    mapper = ChannelKeyRowMapper,
    toParams = { it -> mapOf(
            "id" to it.id,
            "channel_id" to it.channelId,
            "api_key" to it.apiKey,
            "status" to it.status,
            "fail_count" to it.failCount,
            "last_error" to it.lastError,
            "last_used_at" to it.lastUsedAt,
            "deleted" to it.deleted,
            "created_at" to it.createdAt,
            "updated_at" to it.updatedAt
    )},
    softDeleteConfig = neton.database.api.SoftDeleteConfig(deletedColumn = "deleted", notDeletedValue = 0),
    autoFillConfig = neton.database.api.AutoFillConfig(createdAtColumn = "created_at", updatedAtColumn = "updated_at"),
    
)