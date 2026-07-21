// AUTO-GENERATED - DO NOT EDIT
package model

import neton.database.api.EntityMapper
import neton.database.api.Row

internal object QuotaTransactionRowMapper : EntityMapper<QuotaTransaction> {
    override fun map(row: Row): QuotaTransaction = QuotaTransaction(
        id = row.long("id"),
        userId = row.long("user_id"),
        type = row.string("type"),
        amount = row.long("amount"),
        balanceAfter = row.long("balance_after"),
        ref = row.stringOrNull("ref"),
        createdAt = row.longOrNull("created_at")
    )
}