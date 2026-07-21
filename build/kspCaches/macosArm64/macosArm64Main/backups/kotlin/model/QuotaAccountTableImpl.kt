// AUTO-GENERATED - DO NOT EDIT
// 用户已手写 QuotaAccountTable Facade，此处仅生成内部实现
package model

import neton.database.api.Table
import neton.database.adapter.sqlx.SqlxTableAdapter

internal object QuotaAccountTableImpl : Table<QuotaAccount, Long> by SqlxTableAdapter<QuotaAccount, Long>(
    meta = QuotaAccountMeta,
    mapper = QuotaAccountRowMapper,
    toParams = { it -> mapOf(
            "id" to it.id,
            "user_id" to it.userId,
            "balance" to it.balance,
            "version" to it.version,
            "created_at" to it.createdAt,
            "updated_at" to it.updatedAt
    )},
    autoFillConfig = neton.database.api.AutoFillConfig(createdAtColumn = "created_at", updatedAtColumn = "updated_at"),
    
)