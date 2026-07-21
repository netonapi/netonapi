// AUTO-GENERATED - DO NOT EDIT
package model

import neton.database.api.EntityMeta

internal object UsageLogMeta : EntityMeta<UsageLog> {
    override val table = "gateway_usage_logs"
    override val idColumn = "id"
    override val columns = listOf("id", "user_id", "token_id", "channel_id", "request_model", "upstream_model", "prompt_tokens", "completion_tokens", "cache_read_tokens", "cache_write_tokens", "charged", "cost", "ttfb_ms", "latency_ms", "stream", "status", "error_code", "created_at")
    override val columnTypes = mapOf("id" to "BIGINT", "user_id" to "BIGINT", "token_id" to "BIGINT", "channel_id" to "BIGINT", "request_model" to "TEXT", "upstream_model" to "TEXT", "prompt_tokens" to "INTEGER", "completion_tokens" to "INTEGER", "cache_read_tokens" to "INTEGER", "cache_write_tokens" to "INTEGER", "charged" to "BIGINT", "cost" to "BIGINT", "ttfb_ms" to "BIGINT", "latency_ms" to "BIGINT", "stream" to "INTEGER", "status" to "TEXT", "error_code" to "TEXT", "created_at" to "BIGINT")
}