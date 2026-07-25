package logic

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import neton.logging.Logger
import neton.redis.RedisClient
import kotlin.time.Duration.Companion.seconds

/**
 * 令牌级速率限制：RPM（每分钟请求）、TPM（每分钟 token）、并发进行中请求。
 *
 * 没有限流时单个用户就能打爆上游配额与成本——这是中转站的成本敞口，不是可选项。
 *
 * 配了 Redis 走 Redis（多实例共享计数）；没配则退化为**进程内**计数——单实例部署
 * （如默认的 docker compose）仍然有效，多实例时各自独立、总量会放大到实例数倍。
 *
 * 时间窗为固定分钟窗（非滑动）：实现简单、代价是窗口边界处最多可能出现约 2 倍瞬时突发。
 */
@neton.core.annotations.Logic(logger = "logic.gateway-ratelimit")
class RateLimiter(
    private val log: Logger,
    private val redis: RedisClient? = null,
) {

    enum class Decision { ALLOWED, RPM_EXCEEDED, TPM_EXCEEDED, CONCURRENCY_EXCEEDED }

    /** 进入请求：检查并占用 RPM 与并发额度。返回非 ALLOWED 时**不得**继续调上游。 */
    suspend fun acquire(tokenId: Long, rpm: Int?, tpm: Int?, concurrency: Int?, nowMs: Long): Decision {
        if (tpm != null) {
            val used = counterGet(tpmKey(tokenId, nowMs))
            if (used >= tpm) return Decision.TPM_EXCEEDED
        }
        if (rpm != null) {
            val n = counterIncr(rpmKey(tokenId, nowMs), WINDOW_TTL_SEC)
            if (n > rpm) return Decision.RPM_EXCEEDED
        }
        if (concurrency != null) {
            val n = concurrencyIncr(tokenId)
            if (n > concurrency) {
                concurrencyDecr(tokenId)   // 未获准，立刻归还占用
                return Decision.CONCURRENCY_EXCEEDED
            }
        }
        return Decision.ALLOWED
    }

    /** 释放并发占用（无论成功失败都必须调用，否则并发额度会泄漏）。 */
    suspend fun release(tokenId: Long, concurrency: Int?) {
        if (concurrency != null) concurrencyDecr(tokenId)
    }

    /** 结算后累计 TPM（按实际消耗的 token 数）。 */
    suspend fun recordTokens(tokenId: Long, tokens: Int, nowMs: Long) {
        if (tokens <= 0) return
        counterAdd(tpmKey(tokenId, nowMs), tokens.toLong(), WINDOW_TTL_SEC)
    }

    // ===== 存储：Redis 优先，否则进程内 =====

    private suspend fun counterIncr(key: String, ttlSec: Long): Long {
        redis?.let {
            return runCatching {
                val n = it.incr(key)
                if (n == 1L) it.expire(key, ttlSec.seconds)
                n
            }.getOrElse { e ->
                // Redis 不可用不应让网关整体不可用；降级为进程内计数并告警
                log.warn("rate limit: redis unavailable, falling back to in-process counter: ${e.message}")
                localAdd(key, 1, ttlSec)
            }
        }
        return localAdd(key, 1, ttlSec)
    }

    /**
     * 累加任意增量（TPM 一次可能是上千 token）。RedisClient 无 INCRBY，故用读-改-写；
     * 并发下可能少计，对速率限制这种软控制可以接受——不为此付出上千次 INCR 往返。
     */
    private suspend fun counterAdd(key: String, delta: Long, ttlSec: Long): Long {
        val r = redis ?: return localAdd(key, delta, ttlSec)
        return runCatching {
            val next = (r.getValue(key)?.toLongOrNull() ?: 0L) + delta
            r.set(key, next.toString(), ttlSec.seconds)
            next
        }.getOrElse { localAdd(key, delta, ttlSec) }
    }

    private suspend fun counterGet(key: String): Long {
        redis?.let { r -> runCatching { r.getValue(key)?.toLongOrNull() ?: 0L }.getOrNull()?.let { return it } }
        return localGet(key)
    }

    private suspend fun concurrencyIncr(tokenId: Long): Long {
        val key = ccKey(tokenId)
        redis?.let {
            return runCatching {
                val n = it.incr(key)
                it.expire(key, CONCURRENCY_TTL_SEC.seconds)  // 防进程崩溃导致计数永久泄漏
                n
            }.getOrElse { localAdd(key, 1, CONCURRENCY_TTL_SEC) }
        }
        return localAdd(key, 1, CONCURRENCY_TTL_SEC)
    }

    private suspend fun concurrencyDecr(tokenId: Long) {
        val key = ccKey(tokenId)
        redis?.let { r -> runCatching { r.decr(key) }.onSuccess { return }; }
        localAdd(key, -1, CONCURRENCY_TTL_SEC)
    }

    // 进程内兜底计数（带过期），用 Mutex 保证并发安全
    private data class Entry(var value: Long, var expiresAtMs: Long)

    private val mutex = Mutex()
    private val local = mutableMapOf<String, Entry>()

    private suspend fun localAdd(key: String, delta: Long, ttlSec: Long): Long = mutex.withLock {
        val now = nowMillis()
        local.entries.removeAll { it.value.expiresAtMs <= now }
        val e = local.getOrPut(key) { Entry(0, now + ttlSec * 1000) }
        e.value = (e.value + delta).coerceAtLeast(0)
        e.value
    }

    private suspend fun localGet(key: String): Long = mutex.withLock {
        val e = local[key] ?: return 0
        if (e.expiresAtMs <= nowMillis()) { local.remove(key); 0 } else e.value
    }

    private fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    companion object {
        private const val WINDOW_TTL_SEC = 120L
        private const val CONCURRENCY_TTL_SEC = 300L

        fun windowOf(nowMs: Long): Long = nowMs / 60_000
        fun rpmKey(tokenId: Long, nowMs: Long) = "ngrl:rpm:$tokenId:${windowOf(nowMs)}"
        fun tpmKey(tokenId: Long, nowMs: Long) = "ngrl:tpm:$tokenId:${windowOf(nowMs)}"
        fun ccKey(tokenId: Long) = "ngrl:cc:$tokenId"
    }
}
