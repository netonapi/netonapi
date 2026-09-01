package logic

import model.GatewayToken
import model.QuotaAccount
import model.Settlement
import model.UsageLog
import neton.database.dsl.*
import neton.logging.Logger
import neton.security.random.SecureRandom
import table.SettlementTable
import table.UsageLogTable

/**
 * durable settlement 状态机（V004）。设计见 `docs/V004-durable-settlement-design.md`。
 *
 * 核心不变量：
 *  - 资金占位先行——RESERVED 提交后才允许调上游；
 *  - 幂等键**稳定**（`settlement:$settlementId`，非每次新建的 logId），重放不重复扣费；
 *  - 终态（FINALIZED / RELEASED）后预留贡献必须归零；MANUAL_REVIEW 有意保留预留等人工。
 *  - 固定锁序 token → account → settlement（worker 两阶段取件，不反转）。
 */
@neton.core.annotations.Logic(logger = "logic.gateway-settlement")
class SettlementLogic(private val log: Logger, private val quotaLogic: QuotaLogic) {

    // ===== 结果类型 =====

    sealed interface ReserveResult {
        data class Ok(val settlement: Settlement) : ReserveResult
        /** 命中同一客户端幂等键的既有记录 */
        data class Duplicate(val existing: Settlement) : ReserveResult
        data object InsufficientBalance : ReserveResult
        data object InsufficientBudget : ReserveResult
        data object TokenUnavailable : ReserveResult
    }

    sealed interface TransitionResult {
        data object Applied : TransitionResult
        /** 已在目标态或其下游态：幂等成功，非错误 */
        data object AlreadyDone : TransitionResult
        /** 处于不相容状态 */
        data class Conflict(val current: String) : TransitionResult
        data object NotFound : TransitionResult
    }

    // ===== T1: reserve =====

    /**
     * 建立资金占位。settlement INSERT 与账户/令牌预留在**同一事务**，任一条件不满足即整体回滚
     * （无残留预留、无孤儿 settlement）。
     */
    suspend fun reserve(
        userId: Long,
        tokenId: Long,
        requestModel: String,
        endpoint: String,
        reserveAmount: Long,
        pricingSnapshot: String,
        estimated: Boolean,
        clientIdemKey: String? = null,
        requestFingerprint: String? = null,
    ): ReserveResult {
        require(reserveAmount >= 0) { "reserveAmount must be >= 0" }
        // 幂等键命中既有记录：不重复执行上游（§3.3）
        if (clientIdemKey != null) {
            findByIdemKey(userId, tokenId, endpoint, clientIdemKey)?.let { return ReserveResult.Duplicate(it) }
        }
        val sid = SecureRandom.hex(16)  // 128-bit CSPRNG
        val db = neton.database.dbContext()

        return try { db.transaction {
            // 锁序 token → account → settlement
            // ① token 预留：预算足够（无预算=不限）。checked arithmetic 防溢出绕过条件。
            val tok = table.GatewayTokenTable.get(tokenId) ?: return@transaction ReserveResult.TokenUnavailable
            checkedAdd(checkedAdd(tok.quotaUsed, tok.quotaReserved), reserveAmount)
            val tokAffected = db.execute(
                "UPDATE gateway_tokens SET quota_reserved = quota_reserved + :r " +
                    "WHERE id = :tid AND deleted = 0 " +
                    "AND (quota_budget IS NULL OR quota_used + quota_reserved + :r <= quota_budget)",
                mapOf("r" to reserveAmount, "tid" to tokenId),
            )
            if (tokAffected == 0L) {
                // 归因：区分预算不足与令牌不可用，不笼统报冲突
                val cur = table.GatewayTokenTable.get(tokenId)
                return@transaction if (cur == null || cur.deleted != 0) ReserveResult.TokenUnavailable
                else ReserveResult.InsufficientBudget
            }

            // ② 账户预留：可用余额（balance - reserved_balance）足够
            val acc = quotaLogic.account(userId)
            checkedAdd(acc.reservedBalance, reserveAmount)
            val accAffected = db.execute(
                "UPDATE gateway_quota_accounts SET reserved_balance = reserved_balance + :r, version = version + 1 " +
                    "WHERE user_id = :uid AND balance - reserved_balance >= :r",
                mapOf("r" to reserveAmount, "uid" to userId),
            )
            if (accAffected == 0L) {
                // 余额不足：补偿撤销刚才的 token 预留（本事务持该行锁，减回精确等值），净效果为零后正常提交。
                // 不用抛异常触发回滚——驱动可能吞掉/包装异常类型，导致归因丢失并回落成 500。
                db.execute(
                    "UPDATE gateway_tokens SET quota_reserved = GREATEST(quota_reserved - :r, 0) WHERE id = :tid",
                    mapOf("r" to reserveAmount, "tid" to tokenId),
                )
                return@transaction ReserveResult.InsufficientBalance
            }

            // ③ settlement
            val s = SettlementTable.insert(
                Settlement(
                    settlementId = sid,
                    clientIdemKey = clientIdemKey,
                    requestFingerprint = requestFingerprint,
                    userId = userId,
                    tokenId = tokenId,
                    requestModel = requestModel,
                    endpoint = endpoint,
                    status = Settlement.RESERVED,
                    reservedAmount = reserveAmount,
                    pricingSnapshot = pricingSnapshot,
                    estimated = if (estimated) 1 else 0,
                )
            )
            ReserveResult.Ok(s)
        } } catch (e: Throwable) {
            // 唯一键冲突 = 并发同 idem key：重读既有记录当作重复请求处理，不回落成 500
            if (clientIdemKey != null) {
                findByIdemKey(userId, tokenId, endpoint, clientIdemKey)?.let { return ReserveResult.Duplicate(it) }
            }
            throw e
        }
    }

    suspend fun findByIdemKey(userId: Long, tokenId: Long, endpoint: String, idemKey: String): Settlement? =
        SettlementTable.query {
            where {
                and(
                    Settlement::userId eq userId,
                    Settlement::tokenId eq tokenId,
                    Settlement::endpoint eq endpoint,
                    Settlement::clientIdemKey eq idemKey,
                )
            }
        }.list().firstOrNull()

    suspend fun bySid(settlementId: String): Settlement? =
        SettlementTable.oneWhere { Settlement::settlementId eq settlementId }

    // ===== T2 / T3 / T5: 状态推进（纯 CAS，不动钱） =====

    /**
     * T2：首次向上游发请求前。[pricingSnapshot] 非 null 时把「渠道成本快照」一并固化。
     * 重试换渠道时状态不回退（CAS 返回 AlreadyDone），但渠道与快照必须刷新为本次真正打的上游——
     * finalize 的成本要按它算，否则记的是没打过的那家的价。
     */
    suspend fun markUpstreamStarted(
        settlementId: String, channelId: Long, pricingSnapshot: String? = null,
    ): TransitionResult {
        val r = cas(
            settlementId, from = Settlement.RESERVED, to = Settlement.UPSTREAM_STARTED,
            extraSet = "channel_id = :cid", extraParams = mapOf("cid" to channelId),
            downstream = setOf(Settlement.UPSTREAM_STARTED, Settlement.FINALIZE_PENDING, Settlement.FINALIZED),
        )
        if (pricingSnapshot != null) {
            neton.database.dbContext().execute(
                "UPDATE gateway_settlements SET channel_id = :cid, pricing_snapshot = :snap " +
                    "WHERE settlement_id = :sid AND status = :st",
                mapOf(
                    "cid" to channelId, "snap" to pricingSnapshot,
                    "sid" to settlementId, "st" to Settlement.UPSTREAM_STARTED,
                ),
            )
        }
        return r
    }

    /**
     * T3：上游结束，落 usage payload 并转入 durable 重试态。
     * 这一步落库失败且随后进程崩溃 = C3′（真实 usage 丢失），调用方须用有界 NonCancellable 重试压低概率。
     */
    suspend fun markFinalizePending(
        settlementId: String,
        usageJson: String?,
        charged: Long,
        cost: Long,
        outcome: String,
        terminalSeen: Boolean,
        channelId: Long? = null,
        upstreamModel: String = "",
        ttfbMs: Long? = null,
        latencyMs: Long? = null,
        stream: Boolean = false,
        usage: Usage = Usage(),
    ): TransitionResult = cas(
        settlementId, from = Settlement.UPSTREAM_STARTED, to = Settlement.FINALIZE_PENDING,
        extraSet = "usage_json = :u, charged = :c, cost = :co, outcome = :o, terminal_seen = :ts, " +
            "upstream_model = :um, ttfb_ms = :ttfb, latency_ms = :lat, stream = :st, " +
            "prompt_tokens = :pt, completion_tokens = :ct, cache_read_tokens = :crt, cache_write_tokens = :cwt" +
            (if (channelId != null) ", channel_id = :cid" else ""),
        extraParams = buildMap {
            put("u", usageJson); put("c", charged); put("co", cost)
            put("o", outcome); put("ts", if (terminalSeen) 1 else 0)
            put("um", upstreamModel); put("ttfb", ttfbMs); put("lat", latencyMs)
            put("st", if (stream) 1 else 0)
            put("pt", usage.promptTokens); put("ct", usage.completionTokens)
            put("crt", usage.cacheReadTokens); put("cwt", usage.cacheWriteTokens)
            if (channelId != null) put("cid", channelId)
        },
        downstream = setOf(Settlement.FINALIZE_PENDING, Settlement.FINALIZED),
    )

    /**
     * T5：请求释放预留。
     *
     * [from] 允许 RESERVED（未调上游）或 UPSTREAM_STARTED——后者**仅限调用方亲眼观察到**上游在首字节前
     * 失败（连接错/超时/4xx/5xx），此时确知未产生 token 费用，退款安全。
     * **崩溃场景不得走这里**（不知道上游是否已计费），必须由 worker TTL → T9 转人工（见 C2）。
     */
    suspend fun requestRelease(settlementId: String, from: String = Settlement.RESERVED): TransitionResult = cas(
        settlementId, from = from, to = Settlement.RELEASE_PENDING,
        downstream = setOf(Settlement.RELEASE_PENDING, Settlement.RELEASED),
    )

    /** T9：UPSTREAM_STARTED 超时 → 人工（不自动退款：可能已产生真实费用）。 */
    suspend fun markManualReview(settlementId: String, from: String, action: String, error: String?): TransitionResult =
        cas(
            settlementId, from = from, to = Settlement.MANUAL_REVIEW,
            extraSet = "retry_action = :ra, last_error = :err",
            extraParams = mapOf("ra" to action, "err" to error?.take(500)),
            downstream = setOf(Settlement.MANUAL_REVIEW),
        )

    /** T7 / T11：留在本状态，累计 attempts 并退避。 */
    suspend fun scheduleRetry(settlementId: String, status: String, nextRetryAt: Long, error: String?) {
        neton.database.dbContext().execute(
            "UPDATE gateway_settlements SET attempts = attempts + 1, next_retry_at = :n, last_error = :e, " +
                "lease_owner = NULL, lease_until = 0 WHERE settlement_id = :sid AND status = :st",
            mapOf("n" to nextRetryAt, "e" to error?.take(500), "sid" to settlementId, "st" to status),
        )
    }

    /**
     * 通用 CAS 推进。affected=0 时重读区分：已在目标态或下游态 → AlreadyDone（幂等成功）；
     * 不相容态 → Conflict；查无此行 → NotFound。
     */
    private suspend fun cas(
        settlementId: String,
        from: String,
        to: String,
        extraSet: String? = null,
        extraParams: Map<String, Any?> = emptyMap(),
        downstream: Set<String> = emptySet(),
    ): TransitionResult {
        val sql = buildString {
            append("UPDATE gateway_settlements SET status = :to")
            if (extraSet != null) { append(", "); append(extraSet) }
            append(" WHERE settlement_id = :sid AND status = :from")
        }
        val params = buildMap<String, Any?> {
            putAll(extraParams); put("to", to); put("sid", settlementId); put("from", from)
        }
        val n = neton.database.dbContext().execute(sql, params)
        if (n > 0) return TransitionResult.Applied
        val cur = bySid(settlementId) ?: return TransitionResult.NotFound
        return if (cur.status in downstream) TransitionResult.AlreadyDone
        else TransitionResult.Conflict(cur.status)
    }

    // ===== T4: finalize（唯一动钱的路径） =====

    /**
     * 结算：释放预留 + 按实际扣款 + 台账 + usage_log + quotaUsed + 状态 → FINALIZED，**同一事务**。
     * 幂等键 `settlement:$settlementId` 是**稳定**的：重放插台账 0 行即已结算，整体 no-op。
     *
     * @param leaseOwner worker 调用时传入，校验租约仍属于自己；在线路径传 null。
     */
    suspend fun finalize(settlementId: String, leaseOwner: String? = null): TransitionResult {
        val db = neton.database.dbContext()
        return db.transaction {
            // 锁序 token → account → settlement
            val s0 = bySid(settlementId) ?: return@transaction TransitionResult.NotFound
            db.execute("SELECT id FROM gateway_tokens WHERE id = :id FOR UPDATE", mapOf("id" to s0.tokenId))
            db.execute("SELECT id FROM gateway_quota_accounts WHERE user_id = :u FOR UPDATE", mapOf("u" to s0.userId))
            val s = SettlementTable.oneWhere { Settlement::settlementId eq settlementId }
                ?: return@transaction TransitionResult.NotFound

            if (s.status == Settlement.FINALIZED) return@transaction TransitionResult.AlreadyDone
            if (s.status != Settlement.FINALIZE_PENDING) return@transaction TransitionResult.Conflict(s.status)
            if (leaseOwner != null && s.leaseOwner != leaseOwner) return@transaction TransitionResult.Conflict("lease")

            val charged = s.charged
            val r = s.reservedAmount

            // ① 释放预留（两处），无论是否有扣款
            if (r > 0) {
                db.execute(
                    "UPDATE gateway_tokens SET quota_reserved = GREATEST(quota_reserved - :r, 0) WHERE id = :tid",
                    mapOf("r" to r, "tid" to s.tokenId),
                )
                db.execute(
                    "UPDATE gateway_quota_accounts SET reserved_balance = GREATEST(reserved_balance - :r, 0) " +
                        "WHERE user_id = :uid",
                    mapOf("r" to r, "uid" to s.userId),
                )
            }

            // ② usage_log（完整维度，与扣款同事务；worker 重放时维度取自 settlement 行）
            val logId = UsageLogTable.insert(
                UsageLog(
                    userId = s.userId, tokenId = s.tokenId, channelId = s.channelId ?: 0,
                    requestModel = s.requestModel,
                    upstreamModel = s.upstreamModel.ifEmpty { s.requestModel },
                    promptTokens = s.promptTokens, completionTokens = s.completionTokens,
                    cacheReadTokens = s.cacheReadTokens, cacheWriteTokens = s.cacheWriteTokens,
                    charged = charged, cost = s.cost,
                    ttfbMs = s.ttfbMs, latencyMs = s.latencyMs, stream = s.stream,
                    status = s.outcome ?: Settlement.OUTCOME_OK,
                )
            ).id

            // ③ 扣款 + 台账（稳定幂等键；重放插 0 行 → 跳过扣款）
            if (charged > 0) {
                quotaLogic.debitInTx(s.userId, -charged, "consume", "settlement:$settlementId")
                db.execute(
                    "UPDATE gateway_tokens SET quota_used = quota_used + :c WHERE id = :tid AND deleted = 0",
                    mapOf("c" to charged, "tid" to s.tokenId),
                ).let { if (it == 0L) log.warn("finalize: token deleted mid-request sid=$settlementId logId=$logId charged=$charged") }
            }

            // ④ 超预留告警（outcome 而非状态；按实际入账，不静默截断）
            val outcome = if (charged > r) {
                log.error("settlement OVERAGE sid=$settlementId charged=$charged reserved=$r userId=${s.userId}")
                Settlement.OUTCOME_OVERAGE
            } else s.outcome

            val n = db.execute(
                "UPDATE gateway_settlements SET status = :fin, outcome = :o WHERE settlement_id = :sid AND status = :pending",
                mapOf("fin" to Settlement.FINALIZED, "o" to outcome, "sid" to settlementId, "pending" to Settlement.FINALIZE_PENDING),
            )
            if (n == 0L) TransitionResult.Conflict("race") else TransitionResult.Applied
        }
    }

    // ===== T6: release =====

    /** 释放预留（未产生费用的路径）。预留归零，balance 不变。 */
    suspend fun release(settlementId: String, leaseOwner: String? = null): TransitionResult {
        val db = neton.database.dbContext()
        return db.transaction {
            val s0 = bySid(settlementId) ?: return@transaction TransitionResult.NotFound
            db.execute("SELECT id FROM gateway_tokens WHERE id = :id FOR UPDATE", mapOf("id" to s0.tokenId))
            db.execute("SELECT id FROM gateway_quota_accounts WHERE user_id = :u FOR UPDATE", mapOf("u" to s0.userId))
            val s = SettlementTable.oneWhere { Settlement::settlementId eq settlementId }
                ?: return@transaction TransitionResult.NotFound

            if (s.status == Settlement.RELEASED) return@transaction TransitionResult.AlreadyDone
            if (s.status != Settlement.RELEASE_PENDING) return@transaction TransitionResult.Conflict(s.status)
            if (leaseOwner != null && s.leaseOwner != leaseOwner) return@transaction TransitionResult.Conflict("lease")

            val r = s.reservedAmount
            if (r > 0) {
                db.execute(
                    "UPDATE gateway_tokens SET quota_reserved = GREATEST(quota_reserved - :r, 0) WHERE id = :tid",
                    mapOf("r" to r, "tid" to s.tokenId),
                )
                db.execute(
                    "UPDATE gateway_quota_accounts SET reserved_balance = GREATEST(reserved_balance - :r, 0) " +
                        "WHERE user_id = :uid",
                    mapOf("r" to r, "uid" to s.userId),
                )
            }
            val n = db.execute(
                "UPDATE gateway_settlements SET status = :rel WHERE settlement_id = :sid AND status = :pending",
                mapOf("rel" to Settlement.RELEASED, "sid" to settlementId, "pending" to Settlement.RELEASE_PENDING),
            )
            if (n == 0L) TransitionResult.Conflict("race") else TransitionResult.Applied
        }
    }

    private fun checkedAdd(a: Long, b: Long): Long {
        val r = a + b
        check((a xor r) and (b xor r) >= 0L) { "settlement amount overflow: $a + $b" }
        return r
    }
}
