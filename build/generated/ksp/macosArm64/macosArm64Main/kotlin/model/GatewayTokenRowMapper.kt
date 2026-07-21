// AUTO-GENERATED - DO NOT EDIT
package model

import neton.database.api.EntityMapper
import neton.database.api.Row

internal object GatewayTokenRowMapper : EntityMapper<GatewayToken> {
    override fun map(row: Row): GatewayToken = GatewayToken(
        id = row.long("id"),
        userId = row.long("user_id"),
        name = row.string("name"),
        keyHash = row.string("key_hash"),
        keyDisplay = row.string("key_display"),
        quotaBudget = row.longOrNull("quota_budget"),
        quotaUsed = row.long("quota_used"),
        expiresAt = row.longOrNull("expires_at"),
        allowedModels = row.string("allowed_models"),
        allowedIps = row.string("allowed_ips"),
        groupOverride = row.stringOrNull("group_override"),
        status = row.int("status"),
        deleted = row.int("deleted"),
        createdAt = row.longOrNull("created_at"),
        updatedAt = row.longOrNull("updated_at")
    )
}