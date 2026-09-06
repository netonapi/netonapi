package controller.admin.gateway

import dto.PageResponse
import kotlinx.serialization.Serializable
import logic.RedemptionCodeLogic
import model.RedemptionCode
import neton.core.annotations.*
import neton.core.interfaces.Identity
import neton.database.dsl.*
import table.RedemptionCodeTable

@Serializable
data class GenerateRedemptionRequest(
    /** 单张面额 μUSD。整批同面额 —— 一批里混面额会让「按批统计发放总额」失去意义。 */
    val quotaMicro: Long,
    /** 生成张数，上限 [RedemptionCodeLogic.MAX_BATCH]。 */
    val count: Int = 1,
    /** 过期时刻 epoch millis；不传 = 永不过期。 */
    val expiresAt: Long? = null,
    /** 运营备注（活动名 / 客户名）。只进管理端，不出现在用户侧任何响应里。 */
    val note: String? = null,
)

@Serializable
data class GenerateRedemptionVO(
    val batchId: String,
    val count: Int,
    val quotaMicro: Long,
    val expiresAt: Long?,
    val note: String?,
    /**
     * 明文码清单（展示形态，4 组 × 5 字符）。
     *
     * 明文而非哈希，是为了让运营能把码导出发给客户、事后还能在后台翻出来看
     * （见 [RedemptionCode] 的 KDoc）。代价是库泄露即额度泄露，靠码体熵兜住。
     */
    val codes: List<String>,
)

@Serializable
data class RedemptionCodeVO(
    val id: Long,
    /** 展示形态。DB 里存的是无分隔大写裸码，用户输入会先归一化再比对。 */
    val code: String,
    val quotaMicro: Long,
    val status: Int,
    /**
     * 状态文案。**过期是算出来的**（过期码的 status 仍是「未用」，因为没人去改它），
     * 所以不能只丢一个 status 让前端自己拿 expiresAt 猜 —— 两处各算一次迟早算出两个答案。
     */
    val statusText: String,
    val usedUserId: Long?,
    val usedAt: Long?,
    val expiresAt: Long?,
    val batchId: String?,
    val note: String?,
    val createdBy: Long?,
    val createdAt: Long?,
)

@Serializable
data class DisableBatchRedemptionRequest(val batchId: String)

@Serializable
data class DisableBatchRedemptionVO(val batchId: String, val disabled: Int)

@Serializable
data class RedemptionBatchStatsVO(
    val batchId: String,
    val total: Int,
    val unused: Int,
    val used: Int,
    val disabled: Int,
    /** 已过期但状态仍未用：没卖完就失效了，运营要能一眼看出来。 */
    val expired: Int,
    val quotaMicro: Long,
    /** 已核销总额 μUSD（只算真正兑掉的，作废与过期不计）。 */
    val redeemedMicro: Long,
)

/**
 * 兑换码管理端：生成、查询、作废、批次统计。
 *
 * 生成与作废都会记 [RedemptionCode.createdBy] / 日志，因为兑换码是**能直接变成钱的凭据**：
 * 谁造的码、造了多少面额、什么时候作废的，客诉与内部审计都要能追到人。
 * 用户侧唯一的入口是兑换（见 app 的 RedemptionController），任何能让用户自己生成码或改面额的
 * 路径都等于让用户自己印钱。
 *
 * 两道闸门各管一件事，缺一不可：
 *  - **admin 路由组**只管认证 —— 「你是某个管理端账号」。框架对没有 `@Permission` 的路由
 *    **完全不做授权检查**（SecurityPreHandle 第 5 步的条件是 `route.permission != null`），
 *    所以只靠路由组的话，一个只有客服权限的账号照样能造码。
 *  - **`@Permission`** 管授权 —— 「你这个角色能做这件事」。动词拆成 create / list / get /
 *    disable 而不是笼统一个 `gateway:redemption:*`，为的正是「客服能查不能造」这个最常见的分工。
 *
 * super_admin 持有 `*:*:*`，WildcardPermissionEvaluator 直接放行，所以补注解不会把既有部署锁在门外。
 */
@Controller("/gateway/redemption")
class RedemptionCodeAdminController(
    private val redemptionLogic: RedemptionCodeLogic,
) {

    /** 批量生成。返回明文码清单，运营据此导出发放。 */
    @Post("/generate")
    @Permission("gateway:redemption:create")
    suspend fun generate(identity: Identity, @Body request: GenerateRedemptionRequest): GenerateRedemptionVO {
        val g = redemptionLogic.generate(
            quotaMicro = request.quotaMicro,
            count = request.count,
            expiresAt = request.expiresAt,
            note = request.note,
            createdBy = identity.id.toLong(),
        )
        return GenerateRedemptionVO(g.batchId, g.codes.size, g.quotaMicro, g.expiresAt, g.note, g.codes)
    }

    /** 分页查询。status 与 batchId 都可选；两个都不传时列全部（倒序）。 */
    @Get("/page")
    @Permission("gateway:redemption:list")
    suspend fun page(
        @Query pageNo: Int = 1,
        @Query pageSize: Int = 20,
        @Query status: Int? = null,
        @Query batchId: String? = null,
    ): PageResponse<RedemptionCodeVO> {
        val size = pageSize.coerceIn(1, 200)
        val no = pageNo.coerceAtLeast(1)
        val bid = batchId?.trim()?.takeIf { it.isNotEmpty() }
        val r = RedemptionCodeTable.query {
            where {
                and(
                    whenPresent(status) { RedemptionCode::status eq it },
                    whenPresent(bid) { RedemptionCode::batchId eq it },
                )
            }
            orderBy(RedemptionCode::id.desc())
        }.page(no, size)
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        return PageResponse(r.items.map { it.toVO(now) }, r.total, no, size, r.totalPages)
    }

    /** 批次核销情况：总数 / 未用 / 已用 / 作废 / 过期 / 已核销总额。 */
    @Get("/stats/{batchId}")
    @Permission("gateway:redemption:get")
    suspend fun stats(@PathVariable batchId: String): RedemptionBatchStatsVO {
        val s = redemptionLogic.stats(batchId)
        return RedemptionBatchStatsVO(
            s.batchId, s.total, s.unused, s.used, s.disabled, s.expired, s.quotaMicro, s.redeemedMicro,
        )
    }

    /** 作废单张（只能作废未用的）。 */
    @Post("/disable/{id}")
    @Permission("gateway:redemption:disable")
    suspend fun disable(@PathVariable id: Long): RedemptionCodeVO {
        val row = redemptionLogic.disable(id)
        return row.toVO(kotlin.time.Clock.System.now().toEpochMilliseconds())
    }

    /**
     * 整批作废（只影响未用的）。码外泄时用它止损。
     * `disabled = 0` 是合法结果（这批本来就全用完或已作废），不是错误。
     */
    @Post("/disable-batch")
    @Permission("gateway:redemption:disable")
    suspend fun disableBatch(@Body request: DisableBatchRedemptionRequest): DisableBatchRedemptionVO {
        val n = redemptionLogic.disableBatch(request.batchId)
        return DisableBatchRedemptionVO(request.batchId.trim(), n)
    }

    private fun RedemptionCode.toVO(now: Long) = RedemptionCodeVO(
        id = id,
        code = RedemptionCodeLogic.format(code),
        quotaMicro = quotaMicro,
        status = status,
        statusText = when {
            status == RedemptionCodeLogic.STATUS_USED -> "已使用"
            status == RedemptionCodeLogic.STATUS_DISABLED -> "已作废"
            expiresAt != null && expiresAt <= now -> "已过期"
            else -> "未使用"
        },
        usedUserId = usedUserId,
        usedAt = usedAt,
        expiresAt = expiresAt,
        batchId = batchId,
        note = note,
        createdBy = createdBy,
        createdAt = createdAt,
    )
}
