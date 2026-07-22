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

    // ===== 容错：失败计数 / 自动禁用 / 渠道挂起（FailurePolicy 应用侧） =====

    /** 记一次 Key 失败：fail_count+1，达阈值自动禁用（status=2）；若渠道全部 Key 失效则挂起渠道。 */
    suspend fun recordKeyFailure(key: ChannelKey, error: String?) {
        val newCount = FailurePolicy.nextFailCount(key.failCount, success = false)
        val disable = FailurePolicy.shouldDisableKey(newCount)
        ChannelKeyTable.update(
            key.copy(
                failCount = newCount,
                status = if (disable) 2 else key.status,
                lastError = error?.take(500),
            )
        )
        if (disable) {
            log.warn("gateway key auto-disabled channel=${key.channelId} key=${key.id} failCount=$newCount")
            maybeSuspendChannel(key.channelId)
        }
    }

    /** 记一次成功：清零 fail_count，更新 lastUsedAt。 */
    suspend fun recordKeySuccess(key: ChannelKey) {
        ChannelKeyTable.update(key.copy(failCount = 0, lastUsedAt = nowMillis(), lastError = null))
    }

    private suspend fun maybeSuspendChannel(channelId: Long) {
        if (FailurePolicy.shouldSuspendChannel(listKeys(channelId))) {
            updateStatus(channelId, 2)
            log.warn("gateway channel auto-suspended channel=$channelId (no usable key)")
        }
    }

    /** 管理端手动恢复：渠道 status=1，其 Key 全部复位（status=1, failCount=0）。 */
    suspend fun reviveChannel(channelId: Long) {
        listKeys(channelId).forEach { ChannelKeyTable.update(it.copy(status = 1, failCount = 0, lastError = null)) }
        updateStatus(channelId, 1)
    }

    private fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    companion object {
        /** 选择策略纯函数：只取 status=1，按 lastUsedAt 轮转（最久未用优先）。 */
        fun pickAvailable(keys: List<ChannelKey>): ChannelKey? =
            keys.filter { it.status == 1 }.minByOrNull { it.lastUsedAt ?: 0L }
    }
}
