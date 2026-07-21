// AUTO-GENERATED - DO NOT EDIT
package model

import neton.database.api.EntityMeta

internal object ChannelMeta : EntityMeta<Channel> {
    override val table = "gateway_channels"
    override val idColumn = "id"
    override val columns = listOf("id", "name", "type", "base_url", "groups", "models", "model_mapping", "param_override", "priority", "weight", "status", "ttfb_timeout_ms", "idle_timeout_ms", "proxy_url", "cost_discount", "remark", "deleted", "created_at", "updated_at")
    override val columnTypes = mapOf("id" to "BIGINT", "name" to "TEXT", "type" to "TEXT", "base_url" to "TEXT", "groups" to "TEXT", "models" to "TEXT", "model_mapping" to "TEXT", "param_override" to "TEXT", "priority" to "INTEGER", "weight" to "INTEGER", "status" to "INTEGER", "ttfb_timeout_ms" to "BIGINT", "idle_timeout_ms" to "BIGINT", "proxy_url" to "TEXT", "cost_discount" to "TEXT", "remark" to "TEXT", "deleted" to "INTEGER", "created_at" to "BIGINT", "updated_at" to "BIGINT")
}