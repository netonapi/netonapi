package controller.app.gateway

import dto.PageResponse
import kotlinx.serialization.Serializable
import logic.QuotaLogic
import model.QuotaTransaction
import model.UsageLog
import neton.core.annotations.*
import neton.core.interfaces.Identity
import neton.database.dsl.*
import table.QuotaTransactionTable
import table.UsageLogTable

@Serializable
data class MyBalanceVO(
    /** 账面余额 μUSD */
    val balance: Long,
    /** 在途请求占用的预留 μUSD */
    val reserved: Long,
    /** 可用余额 = balance - reserved（预检按此判定） */
    val available: Long,
)

/**
 * 用户查看自己的额度与用量。
 *
 * 所有查询都以 identity 的 userId 强制过滤——不接受请求参数指定用户，
 * 否则任何人都能翻别人的账单。
 */
/**
 * 用户看到的一条用量：模型、tokens、扣费、耗时与结果。不含内部信息——成本价（cost）、渠道、上游实际模型、
 * 计费组的解析来源只给运营看（管理端日志）。
 */
@Serializable
data class MyUsageLogVO(
    val id: Long,
    val tokenId: Long,
    val requestModel: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val cacheReadTokens: Int,
    val cacheWriteTokens: Int,
    val charged: Long,
    val groupCode: String?,
    val ttfbMs: Long?,
    val latencyMs: Long?,
    val stream: Int,
    val status: String,
    val errorCode: String?,
    val createdAt: Long?,
) {
    companion object {
        fun of(l: UsageLog) = MyUsageLogVO(
            id = l.id, tokenId = l.tokenId, requestModel = l.requestModel,
            promptTokens = l.promptTokens, completionTokens = l.completionTokens,
            cacheReadTokens = l.cacheReadTokens, cacheWriteTokens = l.cacheWriteTokens,
            charged = l.charged, groupCode = l.groupCode, ttfbMs = l.ttfbMs, latencyMs = l.latencyMs,
            stream = l.stream, status = l.status, errorCode = l.errorCode, createdAt = l.createdAt,
        )
    }
}

@Controller("/gateway/usage")
class MyUsageController(private val quotaLogic: QuotaLogic, private val statsLogic: logic.UsageStatsLogic) {

    /** 用量汇总：今日 / 近 N 天、按日、按模型、按 Key。 */
    @Get("/stats")
    suspend fun stats(identity: Identity, @Query days: Int = 7): logic.MyUsageStatsVO =
        statsLogic.forUser(identity.id.toLong(), days)

    @Get("/balance")
    suspend fun balance(identity: Identity): MyBalanceVO {
        val acc = quotaLogic.account(identity.id.toLong())
        return MyBalanceVO(
            balance = acc.balance,
            reserved = acc.reservedBalance,
            available = acc.balance - acc.reservedBalance,
        )
    }

    /** 我的调用记录。用量表会持续增长，必须分页。 */
    @Get("/log/page")
    suspend fun logPage(
        identity: Identity,
        @Query pageNo: Int = 1,
        @Query pageSize: Int = 20,
        @Query requestModel: String? = null,
    ): PageResponse<MyUsageLogVO> {
        val uid = identity.id.toLong()
        val size = pageSize.coerceIn(1, 100)
        val no = pageNo.coerceAtLeast(1)
        val r = UsageLogTable.query {
            where {
                and(
                    UsageLog::userId eq uid,
                    whenPresent(requestModel?.takeIf { it.isNotBlank() }) { UsageLog::requestModel eq it },
                )
            }
            orderBy(UsageLog::id.desc())
        }.page(no, size)
        return PageResponse(r.items.map(MyUsageLogVO::of), r.total, no, size, r.totalPages)
    }

    /** 我的额度流水（充值/消费）。 */
    @Get("/transaction/page")
    suspend fun transactionPage(
        identity: Identity,
        @Query pageNo: Int = 1,
        @Query pageSize: Int = 20,
    ): PageResponse<QuotaTransaction> {
        val uid = identity.id.toLong()
        val size = pageSize.coerceIn(1, 100)
        val no = pageNo.coerceAtLeast(1)
        val r = QuotaTransactionTable.query {
            where { QuotaTransaction::userId eq uid }
            orderBy(QuotaTransaction::id.desc())
        }.page(no, size)
        return PageResponse(r.items, r.total, no, size, r.totalPages)
    }
}
