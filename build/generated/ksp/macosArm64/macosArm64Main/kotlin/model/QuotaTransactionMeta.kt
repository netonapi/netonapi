// AUTO-GENERATED - DO NOT EDIT
package model

import neton.database.api.EntityMeta

internal object QuotaTransactionMeta : EntityMeta<QuotaTransaction> {
    override val table = "gateway_quota_transactions"
    override val idColumn = "id"
    override val columns = listOf("id", "user_id", "type", "amount", "balance_after", "ref", "created_at")
    override val columnTypes = mapOf("id" to "BIGINT", "user_id" to "BIGINT", "type" to "TEXT", "amount" to "BIGINT", "balance_after" to "BIGINT", "ref" to "TEXT", "created_at" to "BIGINT")
}