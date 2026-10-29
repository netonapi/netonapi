package logic

import kotlinx.coroutines.runBlocking
import neton.logging.Fields
import neton.logging.Logger
import neton.logging.emptyFields
import neton.redis.RedisClient
import neton.redis.RedisPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

class RateLimiterTest {

    /** 只关心限流决策，日志无副作用。 */
    private object NoopLogger : Logger {
        override fun trace(msg: String, fields: Fields) {}
        override fun debug(msg: String, fields: Fields) {}
        override fun info(msg: String, fields: Fields) {}
        override fun warn(msg: String, fields: Fields, cause: Throwable?) {}
        override fun error(msg: String, fields: Fields, cause: Throwable?) {}
    }

    // 无 Redis：走进程内兜底计数（单实例部署的真实路径）
    private fun limiter() = RateLimiter(NoopLogger, redis = null)

    /**
     * 假 Redis：模拟限流用到的命令并记录调用形状。
     *
     * 真 Lua 语义由 harness S24 在真 Redis 上验证；单测锁的是「一次脚本调用完成 INCRBY+TTL」
     * 这个契约，以及 EVALSHA→EVAL 回退与 Redis 故障降级——这三条恰是读-改-写实现丢增量、
     * 或悄悄退化成进程内计数的地方。
     */
    private class FakeRedis : RedisClient {
        val store = mutableMapOf<String, Long>()
        val ttlSec = mutableMapOf<String, Long>()
        val ttlSets = mutableMapOf<String, Int>()
        var scriptLoads = 0; private set
        var evals = 0; private set
        var evalshas = 0; private set
        /** true = Redis 整体不可用（连接失败）：所有命令都抛。 */
        var broken = false
        private val scripts = mutableMapOf<String, String>()
        private var nextSha = 0

        /** 模拟 Redis 重启 / SCRIPT FLUSH：EVALSHA 之后只能回退 EVAL。 */
        fun dropScripts() = scripts.clear()

        private fun up() { if (broken) throw RuntimeException("redis down") }

        private fun run(body: String, keys: List<String>, args: List<String>): Long {
            up()
            val key = keys.single()
            val cur = store[key] ?: 0L
            if (body.contains("DECRBY")) {
                val n = cur - args[0].toLong()
                if (n <= 0) { store.remove(key); ttlSec.remove(key); return 0 }
                store[key] = n
                touchTtl(key, args[1].toLong(), refresh = false)
                return n
            }
            val n = cur + args[0].toLong()
            store[key] = n
            touchTtl(key, args[1].toLong(), refresh = args[2] == "1")
            return n
        }

        private fun touchTtl(key: String, sec: Long, refresh: Boolean) {
            if (!refresh && ttlSec[key] != null) return
            ttlSec[key] = sec
            ttlSets[key] = (ttlSets[key] ?: 0) + 1
        }

        override suspend fun scriptLoad(script: String): String {
            up(); scriptLoads++
            val sha = "sha-${nextSha++}"
            scripts[sha] = script
            return sha
        }

        override suspend fun evalToLong(script: String, keys: List<String>, args: List<String>): Long? {
            evals++; return run(script, keys, args)
        }

        override suspend fun evalshaToLong(sha: String, keys: List<String>, args: List<String>): Long? {
            up(); evalshas++
            return run(scripts[sha] ?: return null, keys, args)   // 找不到 sha = NOSCRIPT
        }

        override suspend fun getValue(key: String): String? { up(); return store[key]?.toString() }
        override suspend fun set(key: String, value: Any, ttl: Duration?) {
            up(); store[key] = value.toString().toLongOrNull() ?: 0L
            ttl?.let { touchTtl(key, it.inWholeSeconds, refresh = true) }
        }
        override suspend fun incr(key: String): Long { up(); return ((store[key] ?: 0L) + 1).also { store[key] = it } }
        override suspend fun decr(key: String): Long { up(); return ((store[key] ?: 0L) - 1).also { store[key] = it } }
        override suspend fun expire(key: String, ttl: Duration) { up(); touchTtl(key, ttl.inWholeSeconds, refresh = true) }
        override suspend fun delete(key: String) { up(); store.remove(key); ttlSec.remove(key) }
        override suspend fun exists(key: String): Boolean { up(); return store.containsKey(key) }

        // 以下命令限流路径用不到：一旦被调用就说明实现走偏了，直接炸出来
        override suspend fun close() {}
        override suspend fun getBytes(key: String): ByteArray? = throw UnsupportedOperationException()
        override suspend fun setBytes(key: String, value: ByteArray, ttl: Duration?) = throw UnsupportedOperationException()
        override suspend fun hset(key: String, field: String, value: Any) = throw UnsupportedOperationException()
        override suspend fun hget(key: String, field: String): String? = throw UnsupportedOperationException()
        override suspend fun hgetAll(key: String): Map<String, String> = throw UnsupportedOperationException()
        override suspend fun lpush(key: String, value: Any) = throw UnsupportedOperationException()
        override suspend fun rpush(key: String, value: Any) = throw UnsupportedOperationException()
        override suspend fun lpop(key: String): String? = throw UnsupportedOperationException()
        override suspend fun lrange(key: String, start: Int, end: Int): List<String> = throw UnsupportedOperationException()
        override suspend fun sadd(key: String, value: Any) = throw UnsupportedOperationException()
        override suspend fun smembers(key: String): Set<String> = throw UnsupportedOperationException()
        override suspend fun pipeline(block: RedisPipeline.() -> Unit) = throw UnsupportedOperationException()
        override suspend fun collectScanKeys(
            matchPattern: String,
            allowKeysFallback: Boolean,
            block: suspend (String) -> Unit,
        ) = throw UnsupportedOperationException()
        override suspend fun info(section: String?): String? = throw UnsupportedOperationException()
        override suspend fun dbSize(): Long = throw UnsupportedOperationException()
        override suspend fun setIfAbsent(key: String, value: String, pxMillis: Long): Boolean =
            throw UnsupportedOperationException()
    }

    // ===== Redis 路径：计数必须原子、TTL 必须自愈、故障必须降级 =====

    @Test fun redis_tpm_accumulates_every_increment() = runBlocking {
        val fake = FakeRedis()
        val rl = RateLimiter(NoopLogger, redis = fake)
        rl.recordTokens(7, 600, t0)
        rl.recordTokens(7, 500, t0)
        val key = RateLimiter.tpmKey(7, t0)
        assertEquals(1100L, fake.store[key], "两次累计必须是 1100（读-改-写会丢增量）")
        assertEquals(120L, fake.ttlSec[key], "窗口键必须带 TTL，否则永久留在 Redis 里")
        assertEquals(1, fake.ttlSets[key], "固定分钟窗只设一次 TTL：被续期就等于窗口永不结束")
    }

    @Test fun redis_counters_go_through_a_single_script_call() = runBlocking {
        val fake = FakeRedis()
        val rl = RateLimiter(NoopLogger, redis = fake)
        rl.acquire(7, 3, 1000, null, t0)
        rl.recordTokens(7, 100, t0)
        // 读-改-写实现会走 getValue+set；原子实现只走脚本
        assertEquals(0, fake.evals, "首次之后应命中 EVALSHA 缓存")
        assertTrue(fake.evalshas >= 2, "RPM 与 TPM 各一次脚本调用，实际 ${fake.evalshas}")
        assertEquals(1, fake.scriptLoads, "同一脚本只 LOAD 一次")
        assertEquals(1L, fake.store[RateLimiter.rpmKey(7, t0)])
        assertEquals(100L, fake.store[RateLimiter.tpmKey(7, t0)])
    }

    @Test fun script_flush_falls_back_to_eval_instead_of_degrading() = runBlocking {
        val fake = FakeRedis()
        val rl = RateLimiter(NoopLogger, redis = fake)
        rl.recordTokens(7, 10, t0)
        fake.dropScripts()                       // Redis 重启 / SCRIPT FLUSH → NOSCRIPT
        rl.recordTokens(7, 20, t0)
        assertEquals(1, fake.evals, "NOSCRIPT 必须回退 EVAL，而不是把限流悄悄降级到进程内")
        assertEquals(30L, fake.store[RateLimiter.tpmKey(7, t0)], "回退后计数仍要连续")
    }

    @Test fun redis_outage_degrades_to_in_process_counters() = runBlocking {
        val fake = FakeRedis().apply { broken = true }
        val rl = RateLimiter(NoopLogger, redis = fake)
        rl.recordTokens(7, 5000, t0)             // Redis 挂了也要能记账，不能让整个网关不可用
        assertEquals(RateLimiter.Decision.TPM_EXCEEDED, rl.acquire(7, null, 1000, null, t0))
    }

    @Test fun concurrency_counter_is_floored_at_zero_in_redis() = runBlocking {
        val fake = FakeRedis()
        val rl = RateLimiter(NoopLogger, redis = fake)
        rl.release(9, 2)                         // 未占用就释放：不得把计数推到负数（负数=白送额度）
        assertEquals(0L, fake.store[RateLimiter.ccKey(9)] ?: 0L)
        rl.acquire(9, null, null, 2, t0)
        rl.acquire(9, null, null, 2, t0)
        assertEquals(RateLimiter.Decision.CONCURRENCY_EXCEEDED, rl.acquire(9, null, null, 2, t0))
    }

    @Test fun concurrency_counter_always_refreshes_its_ttl() = runBlocking {
        val fake = FakeRedis()
        val rl = RateLimiter(NoopLogger, redis = fake)
        rl.acquire(9, null, null, 5, t0)
        rl.acquire(9, null, null, 5, t0)
        val key = RateLimiter.ccKey(9)
        assertEquals(300L, fake.ttlSec[key])
        assertEquals(2, fake.ttlSets[key], "每次占用都要续期：incr 与 expire 分家时崩溃会留下永久计数")
    }

    private val t0 = 1_700_000_000_000L      // 某分钟窗内
    private val t1 = t0 + 60_000             // 下一分钟窗

    @Test fun no_limits_configured_always_allows() = runBlocking {
        val rl = limiter()
        repeat(50) {
            assertEquals(RateLimiter.Decision.ALLOWED, rl.acquire(1, null, null, null, t0))
        }
    }

    @Test fun rpm_allows_up_to_limit_then_rejects() = runBlocking {
        val rl = limiter()
        repeat(3) { assertEquals(RateLimiter.Decision.ALLOWED, rl.acquire(1, 3, null, null, t0), "第${it + 1}次应放行") }
        assertEquals(RateLimiter.Decision.RPM_EXCEEDED, rl.acquire(1, 3, null, null, t0))
    }

    @Test fun rpm_window_resets_next_minute() = runBlocking {
        val rl = limiter()
        repeat(2) { rl.acquire(1, 2, null, null, t0) }
        assertEquals(RateLimiter.Decision.RPM_EXCEEDED, rl.acquire(1, 2, null, null, t0))
        assertEquals(RateLimiter.Decision.ALLOWED, rl.acquire(1, 2, null, null, t1), "换窗后应重新放行")
    }

    @Test fun limits_are_isolated_per_token() = runBlocking {
        val rl = limiter()
        repeat(2) { rl.acquire(1, 2, null, null, t0) }
        assertEquals(RateLimiter.Decision.RPM_EXCEEDED, rl.acquire(1, 2, null, null, t0))
        assertEquals(RateLimiter.Decision.ALLOWED, rl.acquire(2, 2, null, null, t0), "另一个令牌不应受影响")
    }

    @Test fun tpm_rejects_once_recorded_tokens_reach_limit() = runBlocking {
        val rl = limiter()
        assertEquals(RateLimiter.Decision.ALLOWED, rl.acquire(1, null, 1000, null, t0))
        rl.recordTokens(1, 600, t0)
        assertEquals(RateLimiter.Decision.ALLOWED, rl.acquire(1, null, 1000, null, t0))
        rl.recordTokens(1, 500, t0)   // 累计 1100 ≥ 1000
        assertEquals(RateLimiter.Decision.TPM_EXCEEDED, rl.acquire(1, null, 1000, null, t0))
        assertEquals(RateLimiter.Decision.ALLOWED, rl.acquire(1, null, 1000, null, t1), "换窗后重置")
    }

    @Test fun concurrency_blocks_beyond_limit_and_recovers_on_release() = runBlocking {
        val rl = limiter()
        assertEquals(RateLimiter.Decision.ALLOWED, rl.acquire(1, null, null, 2, t0))
        assertEquals(RateLimiter.Decision.ALLOWED, rl.acquire(1, null, null, 2, t0))
        assertEquals(RateLimiter.Decision.CONCURRENCY_EXCEEDED, rl.acquire(1, null, null, 2, t0))
        rl.release(1, 2)
        assertEquals(RateLimiter.Decision.ALLOWED, rl.acquire(1, null, null, 2, t0), "释放后应可再进入")
    }

    @Test fun rejected_concurrency_does_not_leak_a_slot() = runBlocking {
        // 被拒的请求必须立刻归还占用，否则额度会被“拒绝”本身耗尽
        val rl = limiter()
        rl.acquire(1, null, null, 1, t0)
        repeat(5) { assertEquals(RateLimiter.Decision.CONCURRENCY_EXCEEDED, rl.acquire(1, null, null, 1, t0)) }
        rl.release(1, 1)
        assertEquals(RateLimiter.Decision.ALLOWED, rl.acquire(1, null, null, 1, t0), "多次被拒不应额外占用额度")
    }

    @Test fun rpm_not_consumed_when_tpm_already_exceeded() = runBlocking {
        // TPM 先于 RPM 判定：被 TPM 拒的请求不应消耗 RPM 配额
        val rl = limiter()
        rl.recordTokens(1, 5000, t0)
        assertEquals(RateLimiter.Decision.TPM_EXCEEDED, rl.acquire(1, 2, 1000, null, t0))
        assertEquals(RateLimiter.Decision.TPM_EXCEEDED, rl.acquire(1, 2, 1000, null, t0))
        // TPM 窗口换掉后 RPM 仍是满额
        assertEquals(RateLimiter.Decision.ALLOWED, rl.acquire(1, 2, 1000, null, t1))
    }
}
