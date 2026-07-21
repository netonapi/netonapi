// AUTO-GENERATED - DO NOT EDIT
package model

import neton.database.api.EntityMeta

internal object GatewayTokenMeta : EntityMeta<GatewayToken> {
    override val table = "gateway_tokens"
    override val idColumn = "id"
    override val columns = listOf("id", "user_id", "name", "key_hash", "key_display", "quota_budget", "quota_used", "expires_at", "allowed_models", "allowed_ips", "group_override", "status", "deleted", "created_at", "updated_at")
    override val columnTypes = mapOf("id" to "BIGINT", "user_id" to "BIGINT", "name" to "TEXT", "key_hash" to "TEXT", "key_display" to "TEXT", "quota_budget" to "BIGINT", "quota_used" to "BIGINT", "expires_at" to "BIGINT", "allowed_models" to "TEXT", "allowed_ips" to "TEXT", "group_override" to "TEXT", "status" to "INTEGER", "deleted" to "INTEGER", "created_at" to "BIGINT", "updated_at" to "BIGINT")
}