package logic

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import neton.logging.Logger
import neton.redis.RedisClient

/**
 * 令牌级速率限制：RPM（每分钟请求）、TPM（每分钟 token）、并发进行中请求。
 *
 * 没有限流时单个用户就能打爆上游配额与成本——这是中转站的成本敞口，不是可选项。
 *
 * 配了 Redis 走 Redis（多实例共享计数）；没配则退化为**进程内**计数——单实例部署
 * （如默认的 docker compose）仍然有效，多实例时各自独立、总量会放大到实例数倍。
 *
 * ⚠️ [redis] 带默认值，而 KSP 的 @Logic 装配会跳过带默认值的构造参数（视为「自 provision」），
 * 所以这个参数**不会**被自动注入：必须由 `init.GatewayRuntimeBootstrap` 重绑一次才真的拿到
 * RedisClient。少了那一步不会报错，只是限流永远跑在进程内计数上（多实例部署下等于没限）。
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

    private suspend fun counterIncr(key: String, ttlSec: Long): Long = counterAdd(key, 1, ttlSec)

    /**
     * 原子累加任意增量（TPM 一次可能是上千 token）。
     *
     * 此前是 get + set 读-改-写，注释还写着「RedisClient 无 INCRBY」——事实上 RedisClient 有
     * scriptLoad/evalshaToLong/evalToLong，框架自己的分布式锁就是这么用的。读-改-写在并发下丢增量：
     * 两个请求读到同一个旧值、各自 +N 再写回，TPM 只记到一半，限流随之形同虚设——少计的每个 token
     * 都是真金白银的成本敞口。Lua 脚本在服务端一次执行，天然原子，也省掉一次往返。
     */
    private suspend fun counterAdd(key: String, delta: Long, ttlSec: Long): Long {
        val script = incrScript ?: return localAdd(key, delta, ttlSec)
        return runCatching { script.call(key, listOf(delta.toString(), ttlSec.toString(), TTL_IF_MISSING)) }
            .getOrElse { e ->
                // Redis 不可用不应让网关整体不可用；降级为进程内计数并告警
                log.warn("rate limit: redis unavailable, falling back to in-process counter: ${e.message}")
                localAdd(key, delta, ttlSec)
            }
    }

    private suspend fun counterGet(key: String): Long {
        redis?.let { r -> runCatching { r.getValue(key)?.toLongOrNull() ?: 0L }.getOrNull()?.let { return it } }
        return localGet(key)
    }

    private suspend fun concurrencyIncr(tokenId: Long): Long {
        val key = ccKey(tokenId)
        val script = incrScript ?: return localAdd(key, 1, CONCURRENCY_TTL_SEC)
        // TTL_ALWAYS_REFRESH：占用即续期。incr 与 expire 分成两条命令时，进程在中间崩溃会留下
        // 一个永不过期的并发计数，把令牌永久卡死在「并发已满」——比多算一次严重得多。
        return runCatching { script.call(key, listOf("1", CONCURRENCY_TTL_SEC.toString(), TTL_ALWAYS_REFRESH)) }
            .getOrElse { localAdd(key, 1, CONCURRENCY_TTL_SEC) }
    }

    private suspend fun concurrencyDecr(tokenId: Long) {
        val key = ccKey(tokenId)
        val script = decrScript
        if (script != null) {
            // 脚本把计数地板钳在 0：多归还一次不会变成负数（负数等于白送并发额度），
            // 也不会像 DECR 那样在 key 不存在时留下一个永不过期的 -1。与进程内路径语义一致。
            runCatching { script.call(key, listOf("1", CONCURRENCY_TTL_SEC.toString())) }.onSuccess { return }
        }
        localAdd(key, -1, CONCURRENCY_TTL_SEC)
    }

    /**
     * EVALSHA 缓存：命中即一次往返；NOSCRIPT（Redis 重启 / SCRIPT FLUSH）时清缓存回退 EVAL。
     * 与框架 RedisLockManager 同一套模式。
     */
    private class Script(private val redis: RedisClient, private val body: String) {
        private var sha: String? = null

        suspend fun call(key: String, args: List<String>): Long {
            val cached = sha ?: redis.scriptLoad(body).also { sha = it }
            redis.evalshaToLong(cached, listOf(key), args)?.let { return it }
            sha = null
            return redis.evalToLong(body, listOf(key), args)
                ?: error("redis script returned a non-integer result")
        }
    }

    private val incrScript: Script? by lazy { redis?.let { Script(it, INCR_SCRIPT) } }
    private val decrScript: Script? by lazy { redis?.let { Script(it, DECR_SCRIPT) } }

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

        /** TTL 模式：仅在 key 无 TTL 时设置——固定分钟窗不得被后续请求续期，否则窗口永不结束。 */
        private const val TTL_IF_MISSING = "0"

        /** TTL 模式：每次都刷新——用于并发计数，作为进程崩溃后的自愈兜底。 */
        private const val TTL_ALWAYS_REFRESH = "1"

        /** 原子 INCRBY + TTL（ARGV[1]=增量，ARGV[2]=TTL 秒，ARGV[3]=TTL 模式）。 */
        private const val INCR_SCRIPT = """
local n = redis.call('INCRBY', KEYS[1], ARGV[1])
if ARGV[3] == '1' or redis.call('TTL', KEYS[1]) < 0 then
  redis.call('EXPIRE', KEYS[1], ARGV[2])
end
return n
"""

        /** 原子 DECRBY，地板钳 0（ARGV[1]=减量，ARGV[2]=TTL 秒）。 */
        private const val DECR_SCRIPT = """
local n = redis.call('DECRBY', KEYS[1], ARGV[1])
if n <= 0 then
  redis.call('DEL', KEYS[1])
  return 0
end
if redis.call('TTL', KEYS[1]) < 0 then
  redis.call('EXPIRE', KEYS[1], ARGV[2])
end
return n
"""

        fun windowOf(nowMs: Long): Long = nowMs / 60_000
        fun rpmKey(tokenId: Long, nowMs: Long) = "ngrl:rpm:$tokenId:${windowOf(nowMs)}"
        fun tpmKey(tokenId: Long, nowMs: Long) = "ngrl:tpm:$tokenId:${windowOf(nowMs)}"
        fun ccKey(tokenId: Long) = "ngrl:cc:$tokenId"
    }
}
