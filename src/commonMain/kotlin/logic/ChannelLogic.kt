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

    /**
     * 记一次 Key 失败：列级 CAS 自增 fail_count（并发不丢计数），达阈值自动禁用（status=2）；
     * 渠道全部 Key 失效则挂起。重读最新行避免整对象覆盖（Codex 评审 #3）。
     */
    suspend fun recordKeyFailure(keyId: Long, error: String?) {
        repeat(5) {
            val cur = ChannelKeyTable.get(keyId) ?: return
            if (cur.status != 1) return  // 已被禁用，不再累计
            val newCount = FailurePolicy.nextFailCount(cur.failCount, success = false)
            val disable = FailurePolicy.shouldDisableKey(newCount)
            val updated = ChannelKeyTable.query {
                where { and(ChannelKey::id eq keyId, ChannelKey::failCount eq cur.failCount, ChannelKey::status eq 1) }
            }.update {
                set(ChannelKey::failCount, newCount)
                set(ChannelKey::status, if (disable) 2 else 1)
                set(ChannelKey::lastError, error?.take(500))
            }
            if (updated > 0) {
                if (disable) {
                    log.warn("gateway key auto-disabled channel=${cur.channelId} key=$keyId failCount=$newCount")
                    maybeSuspendChannel(cur.channelId)
                }
                return
            }
        }
    }

    /** 记一次成功：仅当 status=1 时清零 fail_count（不复活已禁用 Key），列级更新。 */
    suspend fun recordKeySuccess(keyId: Long) {
        ChannelKeyTable.query {
            where { and(ChannelKey::id eq keyId, ChannelKey::status eq 1) }
        }.update {
            set(ChannelKey::failCount, 0)
            set(ChannelKey::lastUsedAt, nowMillis())
            set(ChannelKey::lastError, null)
        }
    }

    private suspend fun maybeSuspendChannel(channelId: Long) {
        if (FailurePolicy.shouldSuspendChannel(listKeys(channelId))) {
            updateStatus(channelId, 2)
            log.warn("gateway channel auto-suspended channel=$channelId (no usable key)")
        }
    }

    /**
     * 管理端手动恢复：渠道启用 + 仅复位**自动禁用**（status=2）的 Key；
     * 手动禁用（status=0）保持不动（保留人工语义，Codex 评审 #3）。
     */
    suspend fun reviveChannel(channelId: Long) {
        listKeys(channelId).filter { it.status == 2 }.forEach {
            ChannelKeyTable.update(it.copy(status = 1, failCount = 0, lastError = null))
        }
        updateStatus(channelId, 1)
    }

    private fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    companion object {
        /** 选择策略纯函数：只取 status=1，按 lastUsedAt 轮转（最久未用优先）。 */
        fun pickAvailable(keys: List<ChannelKey>): ChannelKey? =
            keys.filter { it.status == 1 }.minByOrNull { it.lastUsedAt ?: 0L }
    }
}
