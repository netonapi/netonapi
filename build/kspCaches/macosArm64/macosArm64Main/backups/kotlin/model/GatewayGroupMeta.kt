// AUTO-GENERATED - DO NOT EDIT
package model

import neton.database.api.EntityMeta

internal object GatewayGroupMeta : EntityMeta<GatewayGroup> {
    override val table = "gateway_groups"
    override val idColumn = "id"
    override val columns = listOf("id", "name", "ratio", "description", "deleted", "created_at", "updated_at")
    override val columnTypes = mapOf("id" to "BIGINT", "name" to "TEXT", "ratio" to "TEXT", "description" to "TEXT", "deleted" to "INTEGER", "created_at" to "BIGINT", "updated_at" to "BIGINT")
}