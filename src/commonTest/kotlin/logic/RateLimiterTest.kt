package logic

import kotlinx.coroutines.runBlocking
import neton.logging.Fields
import neton.logging.Logger
import neton.logging.emptyFields
import kotlin.test.Test
import kotlin.test.assertEquals

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
