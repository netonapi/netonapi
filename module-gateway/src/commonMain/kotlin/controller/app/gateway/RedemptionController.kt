package controller.app.gateway

import kotlinx.serialization.Serializable
import logic.RedemptionCodeLogic
import neton.core.annotations.*
import neton.core.interfaces.Identity

@Serializable
data class RedeemRequest(
    /** 用户输入的兑换码。带连字符、带空格、大小写混着都行，服务端会归一化后比对。 */
    val code: String,
)

@Serializable
data class RedeemResultVO(
    /** 入账后的余额 μUSD，前端可直接刷新显示 */
    val balance: Long,
    /** 本次入账的面额 μUSD */
    val quotaMicro: Long,
)

/**
 * 用户侧兑换码入口。
 *
 * 用户侧**只有「兑换」这一个动作**：生成、查询、作废全在 admin 路由组
 * （见 controller.admin.gateway.RedemptionCodeAdminController）。这条边界就是钱的安全边界 ——
 * 自助用户若能生成码或改面额，等于自己给自己印钱。
 *
 * 防暴力枚举依赖**码体熵**（20 字符 / 31 字符集 ≈ 99 bit）而不是限流：按每秒百万次尝试算，
 * 撞中任意一张在用的码也远超宇宙年龄。所以这里不额外挂限流。但这是一个**有前提的决定**：
 * 哪天为了「码短一点好念」缩短码长，就必须同时补上按用户的失败计数 —— 熵与限流是同一个
 * 防护的两条腿，砍掉一条另一条必须补上。
 *
 * 失败响应一律**不含面额**（见 [RedemptionCodeLogic.redeem]）：枚举即使命中，也只泄露
 * 「这个码存在 / 已被用过」，不泄露它值多少钱。
 */
@Controller("/gateway/redeem")
class RedemptionController(
    private val redemptionLogic: RedemptionCodeLogic,
) {

    /** 兑换。成功即入账，余额在同一响应里返回。 */
    @Post("")
    suspend fun redeem(identity: Identity, @Body request: RedeemRequest): RedeemResultVO {
        val userId = identity.id.toLong()
        val r = redemptionLogic.redeem(userId, request.code)
        return RedeemResultVO(balance = r.balance, quotaMicro = r.quotaMicro)
    }
}
