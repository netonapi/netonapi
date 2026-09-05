package controller.app.gateway

import kotlinx.serialization.Serializable
import logic.QuotaLogic
import logic.QuotaRechargeLogic
import neton.core.annotations.*
import neton.core.http.ValidationError
import neton.core.http.ValidationException
import neton.core.interfaces.Identity
import neton.logging.Logger
import neton.security.random.SecureRandom

@Serializable
data class MockRechargeRequest(
    /** 充值额度 μUSD */
    val amount: Long,
)

@Serializable
data class RechargeResultVO(val balance: Long, val ref: String)

@Serializable
data class RechargeRequest(
    /** 要支付的法币金额，单位与 payment 的 pay_orders.price 一致。额度由服务端按汇率算出。 */
    val price: Long,
    /** 支付渠道 code。payment **没有默认渠道**，留空一律拒绝（见 [RechargeController.create]）。 */
    val channelCode: String? = null,
)

@Serializable
data class RechargeOrderVO(
    val rechargeId: Long,
    val merchantOrderId: String,
    /** 支付成功后会入账的网关额度（μUSD） */
    val amountMicro: Long,
    val price: Long,
    /** payment 的订单 id 与拉起支付所需信息，前端据此送用户去付款 */
    val orderId: Long,
    val displayMode: String,
    val payload: String,
    val channelOrderNo: String,
)

/**
 * 额度充值。
 *
 * 真实充值见 [create]：payment 下单 → 支付回调 → 装配层监听者按订单号入账。
 * 钱包是法币账本、网关额度是 μUSD 账本，两者通过订单号关联而非共用余额。
 *
 * [mock] 是**模拟充值**，用于打通「付款 → 额度到账 → 能用」这条链路的联调；
 * 它凭空发放额度，因此默认关闭，必须显式设置 `NEWGATE_ENABLE_MOCK_RECHARGE=true` 才可用，
 * 否则一律拒绝——绝不能在生产环境被误开成免费领额度的接口。
 */
@Controller("/gateway/recharge")
class RechargeController(
    private val log: Logger,
    private val quotaLogic: QuotaLogic,
    private val rechargeLogic: QuotaRechargeLogic,
) {

    /**
     * 真实充值：建意图单 → payment 下单 → 返回拉起支付所需信息。
     *
     * 到账不在这个请求里发生：支付平台回调 payment、payment 发布支付成功事件、
     * 装配层的监听者按 merchantOrderId 前缀认单后调 QuotaRechargeLogic.markPaid 入账。
     *
     * 只收 price、不收 amount —— 额度按服务端汇率算（NEWGATE_QUOTA_PER_PRICE_UNIT）。
     * 两个都收等于让客户端自己定价。
     */
    @Post("")
    suspend fun create(identity: Identity, @Body request: RechargeRequest): RechargeOrderVO {
        // 两个参数都在**建意图单之前**验完：payment 没有"默认渠道"这回事
        // （PayOrderLogic.resolveRoute 收到 null 直接判不可用），留空只会在下单那步才 400，
        // 白白在钱据表里留下一张开完就关的单 —— 对账时那是纯噪声。
        val errors = mutableListOf<ValidationError>()
        if (request.price <= 0) {
            errors += ValidationError(path = "price", message = "must be positive")
        }
        if (request.channelCode.isNullOrBlank()) {
            errors += ValidationError(
                path = "channelCode",
                message = "must be a usable pay channel code; payment has no default channel",
            )
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)

        val userId = identity.id.toLong()
        // userIp 暂传 null：可信代理判定（NEWGATE_TRUSTED_PROXIES）在 RelayEngine 那条路径上，
        // 这里不复制一份半吊子的取 IP 逻辑 —— 宁缺勿错，风控字段填错比不填更难查。
        val created = rechargeLogic.create(userId, request.price, request.channelCode, userIp = null)
        return RechargeOrderVO(
            rechargeId = created.rechargeId,
            merchantOrderId = created.merchantOrderId,
            amountMicro = created.amountMicro,
            price = created.price,
            orderId = created.prepay.orderId,
            displayMode = created.prepay.displayMode,
            payload = created.prepay.payload,
            channelOrderNo = created.prepay.channelOrderNo,
        )
    }

    @Post("/mock")
    suspend fun mock(identity: Identity, @Body request: MockRechargeRequest): RechargeResultVO {
        if (!mockEnabled()) {
            throw ValidationException(listOf(ValidationError(
                path = "mock",
                message = "mock recharge is disabled; set NEWGATE_ENABLE_MOCK_RECHARGE=true to enable it in a test environment",
            )))
        }
        if (request.amount <= 0) {
            throw ValidationException(listOf(ValidationError(path = "amount", message = "must be positive")))
        }
        val userId = identity.id.toLong()
        // ref 唯一 → 重复提交不会重复入账（台账 ref 上有唯一约束）
        val ref = "mock:" + SecureRandom.hex(12)
        val balance = quotaLogic.grant(userId, request.amount, type = "grant", ref = ref)
        log.warn("MOCK recharge granted userId=$userId amount=${request.amount} ref=$ref（仅测试环境）")
        return RechargeResultVO(balance, ref)
    }

    private fun mockEnabled(): Boolean =
        neton.core.config.getEnv("NEWGATE_ENABLE_MOCK_RECHARGE")?.lowercase() == "true"
}
