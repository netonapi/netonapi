package logic

import model.Settlement
import neton.logging.Logger
import neton.security.random.SecureRandom
import table.SettlementTable
import neton.database.dsl.*

/**
 * 结算恢复 worker（V004 §9）：把崩溃/失败留下的中间态推进到终态。
 *
 * **不耦合 infra/调度模块**——只暴露 [tick]，由 application/infra 的调度器周期调用。
 *
 * 两阶段取件，避免锁序反转（settlement → token → account 会与在线 finalize 的
 * token → account → settlement 互相等待而死锁）：
 *   A. 短事务只碰 settlement：`FOR UPDATE SKIP LOCKED` 选出 + CAS 写租约，**提交**（释放行锁）；
 *   B. 每条一个新事务，按 token → account → settlement 取锁，校验租约与旧态后推进。
 */
@neton.core.annotations.Logic(logger = "logic.gateway-settlement-worker")
class SettlementWorker(
    private val log: Logger,
    private val settlementLogic: SettlementLogic,
) {

    data class Config(
        /** RESERVED 超过此时长仍未调上游 → 释放预留 */
        val reserveTtlMs: Long = 60_000,
        /** UPSTREAM_STARTED 超过此时长未收尾 → 转人工（不自动退款：可能已产生真实费用） */
        val upstreamTtlMs: Long = 10 * 60_000,
        val leaseMs: Long = 30_000,
        val maxAttempts: Int = 8,
        val batch: Int = 20,
        val baseBackoffMs: Long = 1_000,
    )

    data class TickResult(
        val claimed: Int = 0,
        val finalized: Int = 0,
        val released: Int = 0,
        val manualReview: Int = 0,
        val retried: Int = 0,
    )

    private val owner = SecureRandom.hex(16)

    suspend fun tick(cfg: Config = Config()): TickResult {
        val now = nowMs()
        val ids = claim(cfg, now)
        if (ids.isEmpty()) return TickResult()

        var finalized = 0; var released = 0; var manual = 0; var retried = 0
        for (sid in ids) {
            val s = settlementLogic.bySid(sid) ?: continue
            when (s.status) {
                Settlement.FINALIZE_PENDING ->
                    if (s.attempts >= cfg.maxAttempts) {
                        settlementLogic.markManualReview(sid, s.status, Settlement.ACTION_FINALIZE, s.lastError)
                        manual++
                    } else when (val r = runCatchingDb { settlementLogic.finalize(sid, owner) }) {
                        is SettlementLogic.TransitionResult.Applied,
                        is SettlementLogic.TransitionResult.AlreadyDone -> finalized++
                        else -> { backoff(cfg, s, "finalize -> $r"); retried++ }
                    }

                Settlement.RELEASE_PENDING ->
                    if (s.attempts >= cfg.maxAttempts) {
                        settlementLogic.markManualReview(sid, s.status, Settlement.ACTION_RELEASE, s.lastError)
                        manual++
                    } else when (val r = runCatchingDb { settlementLogic.release(sid, owner) }) {
                        is SettlementLogic.TransitionResult.Applied,
                        is SettlementLogic.TransitionResult.AlreadyDone -> released++
                        else -> { backoff(cfg, s, "release -> $r"); retried++ }
                    }

                // C1：预留后从未调上游 → 安全释放
                Settlement.RESERVED -> {
                    settlementLogic.requestRelease(sid, Settlement.RESERVED)
                    if (settlementLogic.release(sid, null) is SettlementLogic.TransitionResult.Applied) released++
                }

                // C2：已调上游后失联 → **不自动退款**（不知上游是否已计费），保留预留转人工
                Settlement.UPSTREAM_STARTED -> {
                    settlementLogic.markManualReview(
                        sid, s.status, Settlement.ACTION_FINALIZE,
                        "upstream started but never finalized within TTL; reservation held for manual review",
                    )
                    log.warn("settlement stuck after upstream call sid=$sid userId=${s.userId} reserved=${s.reservedAmount} → MANUAL_REVIEW")
                    manual++
                }
            }
        }
        return TickResult(ids.size, finalized, released, manual, retried)
    }

    /** 阶段 A：短事务内选出并打租约，提交后立即释放 settlement 行锁。 */
    private suspend fun claim(cfg: Config, now: Long): List<String> {
        val db = neton.database.dbContext()
        return db.transaction {
            val rows = db.fetchAll(
                """
                SELECT settlement_id FROM gateway_settlements
                 WHERE lease_until <= :now
                   AND ( (status IN ('FINALIZE_PENDING','RELEASE_PENDING') AND next_retry_at <= :now)
                      OR (status = 'RESERVED'         AND created_at + :rttl  <= :now)
                      OR (status = 'UPSTREAM_STARTED' AND created_at + :uttl <= :now) )
                 ORDER BY next_retry_at
                 LIMIT :lim
                 FOR UPDATE SKIP LOCKED
                """.trimIndent(),
                mapOf("now" to now, "rttl" to cfg.reserveTtlMs, "uttl" to cfg.upstreamTtlMs, "lim" to cfg.batch),
            ).mapNotNull { it.stringOrNull("settlement_id")?.trim() }
            log.info("settlement worker claim: ${rows.size} row(s) now=$now reserveTtl=${cfg.reserveTtlMs} upstreamTtl=${cfg.upstreamTtlMs}")
            for (sid in rows) {
                db.execute(
                    "UPDATE gateway_settlements SET lease_owner = :o, lease_until = :until, attempts = attempts + 1 " +
                        "WHERE settlement_id = :sid",
                    mapOf("o" to owner, "until" to now + cfg.leaseMs, "sid" to sid),
                )
            }
            rows
        }
    }

    /** 指数退避 + jitter；DB 死锁/序列化失败（PG 40001 / 40P01）属可重试，不消耗人工额度语义。 */
    private suspend fun backoff(cfg: Config, s: Settlement, error: String) {
        val exp = cfg.baseBackoffMs shl minOf(s.attempts, 6)
        val jitter = (SecureRandom.bytes(1)[0].toInt() and 0xFF) * exp / 512   // 0..~50%
        settlementLogic.scheduleRetry(s.settlementId, s.status, nowMs() + exp + jitter, error)
    }

    private suspend fun runCatchingDb(
        block: suspend () -> SettlementLogic.TransitionResult,
    ): SettlementLogic.TransitionResult = try {
        block()
    } catch (e: Throwable) {
        val msg = e.message ?: ""
        val retryable = listOf("40001", "40P01", "serialization", "deadlock").any { msg.contains(it, ignoreCase = true) }
        log.warn("settlement worker step failed (retryable=$retryable): ${msg.take(200)}")
        SettlementLogic.TransitionResult.Conflict(if (retryable) "db_retryable" else "db_error")
    }

    private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}
