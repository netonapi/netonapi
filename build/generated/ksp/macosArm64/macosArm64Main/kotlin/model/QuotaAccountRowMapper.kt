// AUTO-GENERATED - DO NOT EDIT
package model

import neton.database.api.EntityMapper
import neton.database.api.Row

internal object QuotaAccountRowMapper : EntityMapper<QuotaAccount> {
    override fun map(row: Row): QuotaAccount = QuotaAccount(
        id = row.long("id"),
        userId = row.long("user_id"),
        balance = row.long("balance"),
        version = row.long("version"),
        createdAt = row.longOrNull("created_at"),
        updatedAt = row.longOrNull("updated_at")
    )
}