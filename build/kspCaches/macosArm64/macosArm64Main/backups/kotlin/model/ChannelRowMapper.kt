// AUTO-GENERATED - DO NOT EDIT
package model

import neton.database.api.EntityMapper
import neton.database.api.Row

internal object ChannelRowMapper : EntityMapper<Channel> {
    override fun map(row: Row): Channel = Channel(
        id = row.long("id"),
        name = row.string("name"),
        type = row.string("type"),
        baseUrl = row.string("base_url"),
        groups = row.string("groups"),
        models = row.string("models"),
        modelMapping = row.stringOrNull("model_mapping"),
        paramOverride = row.stringOrNull("param_override"),
        priority = row.int("priority"),
        weight = row.int("weight"),
        status = row.int("status"),
        ttfbTimeoutMs = row.long("ttfb_timeout_ms"),
        idleTimeoutMs = row.long("idle_timeout_ms"),
        proxyUrl = row.stringOrNull("proxy_url"),
        costDiscount = row.string("cost_discount"),
        remark = row.stringOrNull("remark"),
        deleted = row.int("deleted"),
        createdAt = row.longOrNull("created_at"),
        updatedAt = row.longOrNull("updated_at")
    )
}