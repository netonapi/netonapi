package logic

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import model.Channel
import neton.http.testkit.ScriptedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 渠道客户端缓存会回收：渠道改了连接相关字段换新客户端、改 / 删 / 停用时驱逐、空闲超时与超额回收、
 * 进程停止全部关闭；在用的客户端（长流）等租约归还后才关。
 */
class ChannelClientCacheTest {

    private var clock = 1_000L
    private val created = mutableListOf<ScriptedHttpClient>()

    private fun cache(maxEntries: Int = 8, idleTtlMillis: Long = 60_000) = ChannelClientCache(
        create = { ScriptedHttpClient().also { created += it } },
        maxEntries = maxEntries,
        idleTtlMillis = idleTtlMillis,
        now = { clock },
    )

    private fun channel(id: Long, baseUrl: String = "https://api.example.com", proxyUrl: String? = null) =
        Channel(id = id, name = "c$id", type = "openai", baseUrl = baseUrl, proxyUrl = proxyUrl)

    private suspend fun ChannelClientCache.clientOf(ch: Channel) = use(ch) { it as ScriptedHttpClient }

    @Test fun the_same_channel_reuses_its_client() = runBlocking {
        val c = cache()
        assertSame(c.clientOf(channel(1)), c.clientOf(channel(1)))
        assertEquals(1, created.size)
    }

    @Test fun changing_proxy_base_url_or_timeouts_replaces_and_closes_the_old_client() = runBlocking {
        val c = cache()
        val first = c.clientOf(channel(1))
        val proxied = c.clientOf(channel(1, proxyUrl = "http://proxy.example.com:3128"))
        assertNotSame(first, proxied)
        assertTrue(first.isClosed)
        val moved = c.clientOf(channel(1, baseUrl = "https://other.example.com", proxyUrl = "http://proxy.example.com:3128"))
        assertTrue(proxied.isClosed)
        val retimed = c.clientOf(channel(1, baseUrl = "https://other.example.com", proxyUrl = "http://proxy.example.com:3128").copy(idleTimeoutMs = 5_000))
        assertTrue(moved.isClosed)
        assertFalse(retimed.isClosed)
        assertEquals(1, c.size())
    }

    @Test fun evict_closes_the_client_of_an_updated_deleted_or_disabled_channel() = runBlocking {
        val c = cache()
        val a = c.clientOf(channel(1))
        val b = c.clientOf(channel(2))
        c.evict(1)
        assertTrue(a.isClosed)
        assertFalse(b.isClosed)
        assertEquals(1, c.size())
        // 驱逐后再用：按当前配置新建
        assertNotSame(a, c.clientOf(channel(1)))
    }

    @Test fun a_client_in_use_is_closed_only_when_its_last_lease_returns() = runBlocking {
        val c = cache()
        val streaming = CompletableDeferred<ScriptedHttpClient>()
        val finish = CompletableDeferred<Unit>()
        val relay = async {
            c.use(channel(1)) { client ->
                streaming.complete(client as ScriptedHttpClient)
                finish.await()
            }
        }
        val client = streaming.await()
        c.evict(1)
        assertFalse(client.isClosed, "a running stream must keep its client")
        finish.complete(Unit)
        relay.await()
        assertTrue(client.isClosed)
    }

    @Test fun idle_clients_expire() = runBlocking {
        val c = cache(idleTtlMillis = 60_000)
        val idle = c.clientOf(channel(1))
        clock += 59_999
        c.clientOf(channel(2))
        assertFalse(idle.isClosed)
        clock += 60_000
        c.clientOf(channel(2))
        assertTrue(idle.isClosed)
        assertEquals(1, c.size())
    }

    @Test fun the_cache_is_capped_least_recently_used_first() = runBlocking {
        val c = cache(maxEntries = 2)
        val one = c.clientOf(channel(1)); clock++
        val two = c.clientOf(channel(2)); clock++
        c.clientOf(channel(1)); clock++          // 1 刚用过，2 成了最久未用
        c.clientOf(channel(3))
        assertTrue(two.isClosed)
        assertFalse(one.isClosed)
        assertEquals(2, c.size())
    }

    @Test fun close_shuts_every_client_and_refuses_new_use() = runBlocking {
        val c = cache()
        val a = c.clientOf(channel(1))
        val b = c.clientOf(channel(2))
        c.close()
        assertTrue(a.isClosed && b.isClosed)
        assertFailsWith<IllegalStateException> { c.clientOf(channel(1)) }
        Unit
    }
}
