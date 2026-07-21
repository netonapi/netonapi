// AUTO-GENERATED - DO NOT EDIT
package model

import neton.database.api.EntityMapper
import neton.database.api.Row

internal object UsageLogRowMapper : EntityMapper<UsageLog> {
    override fun map(row: Row): UsageLog = UsageLog(
        id = row.long("id"),
        userId = row.long("user_id"),
        tokenId = row.long("token_id"),
        channelId = row.long("channel_id"),
        requestModel = row.string("request_model"),
        upstreamModel = row.string("upstream_model"),
        promptTokens = row.int("prompt_tokens"),
        completionTokens = row.int("completion_tokens"),
        cacheReadTokens = row.int("cache_read_tokens"),
        cacheWriteTokens = row.int("cache_write_tokens"),
        charged = row.long("charged"),
        cost = row.long("cost"),
        ttfbMs = row.longOrNull("ttfb_ms"),
        latencyMs = row.longOrNull("latency_ms"),
        stream = row.int("stream"),
        status = row.string("status"),
        errorCode = row.stringOrNull("error_code"),
        createdAt = row.longOrNull("created_at")
    )
}