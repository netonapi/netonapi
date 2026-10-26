package logic

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import model.Channel
import neton.http.client.HttpClient

/**
 * 渠道出站客户端缓存：每条渠道一个受控客户端（各自的代理与超时），按渠道 id 存。
 *
 * 不缓存的话每次转发都新建连接池；只缓存不回收的话，改过代理 / 地址 / 超时的渠道、删掉或停用的渠道、
 * 以及测试时随手建的渠道，它们的客户端（和连接池里的空闲连接）一直挂到进程退出。所以：
 *
 * - **指纹**：影响连接的字段（baseUrl、proxyUrl、ttfb / idle 超时）。取用时指纹变了就换新客户端，旧的退役；
 * - **主动驱逐**：渠道修改、删除、停用时 [evict]（[ChannelLogic] 调）——不等下一次取用；
 * - **空闲回收**：超过 [idleTtlMillis] 没用过的、以及超出 [maxEntries] 的最久未用者退役；
 * - **关闭**：[close] 在进程停止时退役全部，之后不再发客户端。
 *
 * 退役 ≠ 立即关闭：正在用它的请求（长流可达十分钟）持有租约（[use]），最后一个租约归还时才关。
 * 所以取客户端只能经 [use]，不能把客户端拿出去存着。
 */
class ChannelClientCache(
    private val create: (Channel) -> HttpClient,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val idleTtlMillis: Long = DEFAULT_IDLE_TTL_MILLIS,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    init {
        require(maxEntries >= 1) { "maxEntries must be at least 1: $maxEntries" }
        require(idleTtlMillis > 0) { "idleTtlMillis must be positive: $idleTtlMillis" }
    }

    private class Entry(val fingerprint: String, val client: HttpClient, var lastUsed: Long) {
        var leases = 0
        var retired = false
    }

    private val mutex = Mutex()
    private val entries = LinkedHashMap<Long, Entry>()
    private var closed = false

    /** 当前缓存的渠道数（不含已退役、等租约归还的）。 */
    suspend fun size(): Int = mutex.withLock { entries.size }

    /** 用 [channel] 的客户端执行 [block]；期间客户端不会被关闭，哪怕渠道被改、被删或缓存在回收。 */
    suspend fun <T> use(channel: Channel, block: suspend (HttpClient) -> T): T {
        val entry = acquire(channel)
        try {
            return block(entry.client)
        } finally {
            release(entry)
        }
    }

    /** 渠道被修改 / 删除 / 停用：退役它的客户端（在用的等租约归还后关闭）。 */
    suspend fun evict(channelId: Long) {
        val toClose = mutex.withLock { entries.remove(channelId)?.let { retire(it) } }
        toClose?.close()
    }

    /** 进程停止：退役全部；之后 [use] 失败。 */
    suspend fun close() {
        val toClose = mutex.withLock {
            closed = true
            val all = entries.values.mapNotNull { retire(it) }
            entries.clear()
            all
        }
        for (c in toClose) c.close()
    }

    private suspend fun acquire(channel: Channel): Entry {
        val toClose = mutableListOf<HttpClient>()
        val entry = mutex.withLock {
            check(!closed) { "channel client cache is closed" }
            val t = now()
            val fingerprint = fingerprintOf(channel)
            val current = entries[channel.id]?.takeIf { it.fingerprint == fingerprint }
            val entry = current ?: Entry(fingerprint, create(channel), t).also { fresh ->
                entries.remove(channel.id)?.let { stale -> retire(stale)?.let(toClose::add) }
                entries[channel.id] = fresh
            }
            entry.leases++
            entry.lastUsed = t
            // 最近用过的排到末尾：超额时从头（最久未用）开始退役
            entries.remove(channel.id)
            entries[channel.id] = entry
            sweep(t, keep = channel.id, into = toClose)
            entry
        }
        for (c in toClose) c.close()
        return entry
    }

    private suspend fun release(entry: Entry) {
        val toClose = mutex.withLock {
            entry.leases--
            entry.lastUsed = now()
            if (entry.retired && entry.leases == 0) entry.client else null
        }
        toClose?.close()
    }

    /** 空闲超时的、超出上限的（最久未用者先）退役。持锁调用。 */
    private fun sweep(t: Long, keep: Long, into: MutableList<HttpClient>) {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val (id, e) = it.next()
            if (id == keep) continue
            val idle = e.leases == 0 && t - e.lastUsed >= idleTtlMillis
            if (idle || entries.size > maxEntries) {
                it.remove()
                retire(e)?.let(into::add)
            }
        }
    }

    /** 标记退役；没有租约时返回要关闭的客户端（由调用方在锁外关）。持锁调用。 */
    private fun retire(e: Entry): HttpClient? {
        e.retired = true
        return if (e.leases == 0) e.client else null
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 256
        const val DEFAULT_IDLE_TTL_MILLIS: Long = 10 * 60_000L

        /** 影响连接行为的渠道字段：任何一个变了都得换客户端。 */
        fun fingerprintOf(channel: Channel): String =
            "${channel.baseUrl}|${channel.effectiveProxyUrl() ?: ""}|${channel.ttfbTimeoutMs}|${channel.idleTimeoutMs}"
    }
}
