// AUTO-GENERATED - DO NOT EDIT
package model

import neton.database.api.EntityMapper
import neton.database.api.Row

internal object ModelPriceRowMapper : EntityMapper<ModelPrice> {
    override fun map(row: Row): ModelPrice = ModelPrice(
        id = row.long("id"),
        model = row.string("model"),
        inputPrice = row.string("input_price"),
        outputPrice = row.string("output_price"),
        cacheReadPrice = row.string("cache_read_price"),
        cacheWritePrice = row.string("cache_write_price"),
        perRequestPrice = row.longOrNull("per_request_price"),
        saleOverride = row.stringOrNull("sale_override"),
        source = row.string("source"),
        deleted = row.int("deleted"),
        createdAt = row.longOrNull("created_at"),
        updatedAt = row.longOrNull("updated_at")
    )
}