// AUTO-GENERATED - DO NOT EDIT
package model

import neton.database.api.EntityMeta

internal object ModelPriceMeta : EntityMeta<ModelPrice> {
    override val table = "gateway_model_prices"
    override val idColumn = "id"
    override val columns = listOf("id", "model", "input_price", "output_price", "cache_read_price", "cache_write_price", "per_request_price", "sale_override", "source", "deleted", "created_at", "updated_at")
    override val columnTypes = mapOf("id" to "BIGINT", "model" to "TEXT", "input_price" to "TEXT", "output_price" to "TEXT", "cache_read_price" to "TEXT", "cache_write_price" to "TEXT", "per_request_price" to "BIGINT", "sale_override" to "TEXT", "source" to "TEXT", "deleted" to "INTEGER", "created_at" to "BIGINT", "updated_at" to "BIGINT")
}