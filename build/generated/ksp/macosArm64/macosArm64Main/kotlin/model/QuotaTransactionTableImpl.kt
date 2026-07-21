// AUTO-GENERATED - DO NOT EDIT
// 用户已手写 QuotaTransactionTable Facade，此处仅生成内部实现
package model

import neton.database.api.Table
import neton.database.adapter.sqlx.SqlxTableAdapter

internal object QuotaTransactionTableImpl : Table<QuotaTransaction, Long> by SqlxTableAdapter<QuotaTransaction, Long>(
    meta = QuotaTransactionMeta,
    mapper = QuotaTransactionRowMapper,
    toParams = { it -> mapOf(
            "id" to it.id,
            "user_id" to it.userId,
            "type" to it.type,
            "amount" to it.amount,
            "balance_after" to it.balanceAfter,
            "ref" to it.ref,
            "created_at" to it.createdAt
    )},
    autoFillConfig = neton.database.api.AutoFillConfig(createdAtColumn = "created_at"),
    
)