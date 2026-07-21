// AUTO-GENERATED - DO NOT EDIT
// 用户已手写 UsageLogTable Facade，此处仅生成内部实现
package model

import neton.database.api.Table
import neton.database.adapter.sqlx.SqlxTableAdapter

internal object UsageLogTableImpl : Table<UsageLog, Long> by SqlxTableAdapter<UsageLog, Long>(
    meta = UsageLogMeta,
    mapper = UsageLogRowMapper,
    toParams = { it -> mapOf(
            "id" to it.id,
            "user_id" to it.userId,
            "token_id" to it.tokenId,
            "channel_id" to it.channelId,
            "request_model" to it.requestModel,
            "upstream_model" to it.upstreamModel,
            "prompt_tokens" to it.promptTokens,
            "completion_tokens" to it.completionTokens,
            "cache_read_tokens" to it.cacheReadTokens,
            "cache_write_tokens" to it.cacheWriteTokens,
            "charged" to it.charged,
            "cost" to it.cost,
            "ttfb_ms" to it.ttfbMs,
            "latency_ms" to it.latencyMs,
            "stream" to it.stream,
            "status" to it.status,
            "error_code" to it.errorCode,
            "created_at" to it.createdAt
    )},
    autoFillConfig = neton.database.api.AutoFillConfig(createdAtColumn = "created_at"),
    
)