// AUTO-GENERATED - DO NOT EDIT
package model

import neton.database.api.EntityMapper
import neton.database.api.Row

internal object ChannelKeyRowMapper : EntityMapper<ChannelKey> {
    override fun map(row: Row): ChannelKey = ChannelKey(
        id = row.long("id"),
        channelId = row.long("channel_id"),
        apiKey = row.string("api_key"),
        status = row.int("status"),
        failCount = row.int("fail_count"),
        lastError = row.stringOrNull("last_error"),
        lastUsedAt = row.longOrNull("last_used_at"),
        deleted = row.int("deleted"),
        createdAt = row.longOrNull("created_at"),
        updatedAt = row.longOrNull("updated_at")
    )
}