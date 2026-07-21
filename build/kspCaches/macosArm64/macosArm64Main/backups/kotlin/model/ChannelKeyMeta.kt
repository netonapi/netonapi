// AUTO-GENERATED - DO NOT EDIT
package model

import neton.database.api.EntityMeta

internal object ChannelKeyMeta : EntityMeta<ChannelKey> {
    override val table = "gateway_channel_keys"
    override val idColumn = "id"
    override val columns = listOf("id", "channel_id", "api_key", "status", "fail_count", "last_error", "last_used_at", "deleted", "created_at", "updated_at")
    override val columnTypes = mapOf("id" to "BIGINT", "channel_id" to "BIGINT", "api_key" to "TEXT", "status" to "INTEGER", "fail_count" to "INTEGER", "last_error" to "TEXT", "last_used_at" to "BIGINT", "deleted" to "INTEGER", "created_at" to "BIGINT", "updated_at" to "BIGINT")
}