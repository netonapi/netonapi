// AUTO-GENERATED - DO NOT EDIT
package model

import neton.database.api.EntityMapper
import neton.database.api.Row

internal object GatewayGroupRowMapper : EntityMapper<GatewayGroup> {
    override fun map(row: Row): GatewayGroup = GatewayGroup(
        id = row.long("id"),
        name = row.string("name"),
        ratio = row.string("ratio"),
        description = row.stringOrNull("description"),
        deleted = row.int("deleted"),
        createdAt = row.longOrNull("created_at"),
        updatedAt = row.longOrNull("updated_at")
    )
}