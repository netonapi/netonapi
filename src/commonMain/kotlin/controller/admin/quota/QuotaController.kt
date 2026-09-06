package controller.admin.quota

import kotlinx.serialization.Serializable
import logic.QuotaLogic
import model.QuotaAccount
import model.QuotaTransaction
import neton.core.annotations.*

@Serializable
data class GrantQuotaRequest(val userId: Long, val amount: Long, val ref: String? = null)

/**
 * @param amount 撤回量，必须是正数。非正数会在 logic 层被拒成 400 —— `revoke(-100)` 就是一次
 *   发放，放行它等于让持有 gateway:quota:revoke 的人绕过 gateway:quota:grant。
 * @param ref 幂等键，**必填**（与 grant 的可选不同）。同一个 ref 只会扣一次，重复提交返回
 *   撤回前的余额。建议 `revoke:<工单号>`。
 */
@Serializable
data class RevokeQuotaRequest(val userId: Long, val amount: Long, val ref: String)

/**
 * 撤回结果。把 reservedBalance 与 available 一起返回，是因为撤回**允许把余额扣成负数**
 * （见 [logic.QuotaLogic.revoke]），而只回一个 balance 会让运营看不出这次撤回落在什么处境上：
 * 余额 0 且有在途预留，与余额 0 且无任何预留，是两件需要不同后续动作的事。
 *
 * 撤回量叫 [RevokeQuotaResultVO.revokedAmount] 而不是跟着请求叫 `amount`，是刻意的不对称：
 * 台账 [model.QuotaTransaction.amount] 记的是**带符号的余额变动**（撤回为负），而这个 VO 里的
 * 撤回量恒正。两个 `amount` 同名不同义，光靠 KDoc 兜不住 —— 歧义活在 JSON 里，前端看到的是
 * `"amount": 30000` 挨着 `"balance": 99925000`，得翻 Kotlin 源码才知道符号约定是哪一套。
 * 别为了与请求对称把它改回 `amount`：这个端点落地当天就有人（写 harness 的我）照着 JSON 字段名
 * 把断言写成了 `-30000`，其余五项数字全对、只有那一项翻红。改名是唯一在 JSON 层面也成立的修法。
 */
@Serializable
data class RevokeQuotaResultVO(
    val userId: Long,
    /** 撤回后的余额，可能为负 */
    val balance: Long,
    /** 在途请求占位，撤回不动它 */
    val reservedBalance: Long,
    /** balance - reservedBalance，可能为负；为负时新请求的预留会被拒 */
    val available: Long,
    /** 这次撤回的量，恒正。台账里对应行的 amount 是它的相反数（见类级 KDoc） */
    val revokedAmount: Long,
    val ref: String,
)

@Controller("/gateway/quota")
class QuotaController(private val quotaLogic: QuotaLogic) {

    @Get("/get-by-user/{userId}")
    @Permission("gateway:quota:get")
    suspend fun getByUser(@PathVariable userId: Long): QuotaAccount = quotaLogic.account(userId)

    @Post("/grant")
    @Permission("gateway:quota:grant")
    suspend fun grant(@Body request: GrantQuotaRequest): Long =
        quotaLogic.grant(request.userId, request.amount, ref = request.ref)

    /**
     * 撤回已发放的额度。独立权限而不是复用 gateway:quota:grant：发放与撤回在风控上是两件事
     * （撤回直接减少用户已有余额，误操作的后果落在用户身上），要能分别授予。
     */
    @Post("/revoke")
    @Permission("gateway:quota:revoke")
    suspend fun revoke(@Body request: RevokeQuotaRequest): RevokeQuotaResultVO {
        val balance = quotaLogic.revoke(request.userId, request.amount, request.ref)
        // 事务提交后再读账户：预留是别的请求在动的，读到的就是撤回这一刻的真实处境。
        val acc = quotaLogic.account(request.userId)
        return RevokeQuotaResultVO(
            userId = request.userId,
            balance = balance,
            reservedBalance = acc.reservedBalance,
            available = acc.balance - acc.reservedBalance,
            revokedAmount = request.amount,
            ref = request.ref,
        )
    }

    @Get("/transactions/{userId}")
    @Permission("gateway:quota:get")
    suspend fun transactions(@PathVariable userId: Long): List<QuotaTransaction> =
        quotaLogic.transactions(userId)
}
