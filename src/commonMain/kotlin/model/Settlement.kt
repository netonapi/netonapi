package model

import kotlinx.serialization.Serializable
import neton.database.annotations.*

/**
 * 结算记录（V004）：一次真实上游执行的资金占位与最终账务，同时充当 durable queue。
 * 状态机与崩溃恢复见 `docs/V004-durable-settlement-design.md`。
 */
@Serializable
@Table("gateway_settlements")
data class Settlement(
    @Id val id: Long = 0,
    /** CSPRNG 128-bit hex，账务主键（非客户端可控） */
    val settlementId: String,
    /** SHA-256(客户端 Idempotency-Key)；null = 客户端未提供 */
    val clientIdemKey: String? = null,
    /** SHA-256(长度前缀编码的请求摘要) */
    val requestFingerprint: String? = null,
    val userId: Long,
    val tokenId: Long,
    /** reserve 时未知，T2（首次调上游）时写入 */
    val channelId: Long? = null,
    val requestModel: String,
    val endpoint: String,
    val status: String,
    /** FINALIZE / RELEASE：进 MANUAL_REVIEW 时记录当时动作 */
    val retryAction: String? = null,
    /** 预留额 μUSD（上界） */
    val reservedAmount: Long = 0,
    val charged: Long = 0,
    val cost: Long = 0,
    /** reserve 时固化的定价快照 JSON；finalize 必须按它计价 */
    val pricingSnapshot: String = "",
    /** T3 落库的 usage payload */
    val usageJson: String? = null,
    // usage_log 维度：finalize（含 worker 重放）在同一事务内写出完整日志
    val upstreamModel: String = "",
    val ttfbMs: Long? = null,
    val latencyMs: Long? = null,
    val stream: Int = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    /** 1 = 输入 token 为保守估算（无 tokenizer） */
    val estimated: Int = 0,
    val terminalSeen: Int = 0,
    /** ok / partial / error / OVERAGE（OVERAGE 是 outcome 不是状态） */
    val outcome: String? = null,
    val attempts: Int = 0,
    val nextRetryAt: Long = 0,
    /** worker 实例标识（两阶段取件，避免锁序反转） */
    val leaseOwner: String? = null,
    val leaseUntil: Long = 0,
    val lastError: String? = null,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
) {
    companion object {
        const val RESERVED = "RESERVED"
        const val UPSTREAM_STARTED = "UPSTREAM_STARTED"
        const val FINALIZE_PENDING = "FINALIZE_PENDING"
        const val FINALIZED = "FINALIZED"
        const val RELEASE_PENDING = "RELEASE_PENDING"
        const val RELEASED = "RELEASED"
        const val MANUAL_REVIEW = "MANUAL_REVIEW"

        const val ACTION_FINALIZE = "FINALIZE"
        const val ACTION_RELEASE = "RELEASE"

        const val OUTCOME_OK = "ok"
        const val OUTCOME_PARTIAL = "partial"
        const val OUTCOME_ERROR = "error"
        const val OUTCOME_OVERAGE = "OVERAGE"

        /** 终态：不再由 worker 推进（MANUAL_REVIEW 也不自动推进，但仍持有预留）。 */
        val TERMINAL = setOf(FINALIZED, RELEASED, MANUAL_REVIEW)
    }
}
