package controller.admin.settlement

import kotlinx.serialization.Serializable
import logic.SettlementLogic
import logic.SettlementWorker
import model.Settlement
import neton.core.annotations.*
import neton.database.dsl.*
import table.SettlementTable

@Serializable
data class TickRequest(
    val reserveTtlMs: Long? = null,
    val upstreamTtlMs: Long? = null,
    val maxAttempts: Int? = null,
    val batch: Int? = null,
)

@Serializable
data class TickResponse(
    val claimed: Int, val finalized: Int, val released: Int,
    val manualReview: Int, val retried: Int,
)

/**
 * 结算运维端点。
 *
 * [tick] 供运维手动催办与集成测试触发；**生产的周期调度由 application/infra 侧装配**
 * （worker 本身不耦合调度模块）。
 */
@Controller("/gateway/settlement")
class SettlementController(
    private val worker: SettlementWorker,
    private val settlementLogic: SettlementLogic,
) {

    @Post("/tick")
    @Permission("gateway:settlement:tick")
    suspend fun tick(@Body request: TickRequest?): TickResponse {
        val d = SettlementWorker.Config()
        val cfg = SettlementWorker.Config(
            reserveTtlMs = request?.reserveTtlMs ?: d.reserveTtlMs,
            upstreamTtlMs = request?.upstreamTtlMs ?: d.upstreamTtlMs,
            maxAttempts = request?.maxAttempts ?: d.maxAttempts,
            batch = request?.batch ?: d.batch,
        )
        val r = worker.tick(cfg)
        return TickResponse(r.claimed, r.finalized, r.released, r.manualReview, r.retried)
    }

    /** 待人工处理的结算（预留仍被持有，需要人工判断上游是否已计费）。 */
    @Get("/manual-review")
    @Permission("gateway:settlement:list")
    suspend fun manualReview(): List<Settlement> =
        SettlementTable.query { where { Settlement::status eq Settlement.MANUAL_REVIEW } }.list()

    @Get("/get/{settlementId}")
    @Permission("gateway:settlement:list")
    suspend fun get(@PathVariable settlementId: String): Settlement? =
        settlementLogic.bySid(settlementId)
}
