package logic

import model.Channel
import model.ChannelKey
import neton.database.dsl.*
import neton.logging.Logger
import table.ChannelKeyTable
import table.ChannelTable

@neton.core.annotations.Logic(logger = "logic.gateway-channel")
class ChannelLogic(private val log: Logger) {

    suspend fun create(channel: Channel): Long = ChannelTable.insert(channel).id

    suspend fun get(id: Long): Channel? = ChannelTable.get(id)

    suspend fun update(channel: Channel) { ChannelTable.update(channel) }

    suspend fun delete(id: Long) {
        ChannelTable.destroy(id)
        log.info("gateway channel deleted id=$id")
    }

    suspend fun list(): List<Channel> = ChannelTable.findAll()

    suspend fun listEnabled(): List<Channel> =
        ChannelTable.query { where { Channel::status eq 1 } }.list()

    suspend fun updateStatus(id: Long, status: Int) {
        val current = get(id) ?: return
        ChannelTable.update(current.copy(status = status))
    }

    // ===== Key 管理 =====

    suspend fun listKeys(channelId: Long): List<ChannelKey> =
        ChannelKeyTable.query { where { ChannelKey::channelId eq channelId } }.list()

    suspend fun addKey(channelId: Long, apiKey: String): Long =
        ChannelKeyTable.insert(ChannelKey(channelId = channelId, apiKey = apiKey)).id

    suspend fun deleteKey(id: Long) { ChannelKeyTable.destroy(id) }

    suspend fun pickKey(channelId: Long): ChannelKey? = pickAvailable(listKeys(channelId))

    companion object {
        /** 选择策略纯函数：只取 status=1，按 lastUsedAt 轮转（最久未用优先）。 */
        fun pickAvailable(keys: List<ChannelKey>): ChannelKey? =
            keys.filter { it.status == 1 }.minByOrNull { it.lastUsedAt ?: 0L }
    }
}
