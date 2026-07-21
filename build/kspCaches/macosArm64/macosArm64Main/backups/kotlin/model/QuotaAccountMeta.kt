// AUTO-GENERATED - DO NOT EDIT
package model

import neton.database.api.EntityMeta

internal object QuotaAccountMeta : EntityMeta<QuotaAccount> {
    override val table = "gateway_quota_accounts"
    override val idColumn = "id"
    override val columns = listOf("id", "user_id", "balance", "version", "created_at", "updated_at")
    override val columnTypes = mapOf("id" to "BIGINT", "user_id" to "BIGINT", "balance" to "BIGINT", "version" to "BIGINT", "created_at" to "BIGINT", "updated_at" to "BIGINT")
}