package controller.app.gateway

import logic.GatewayErrorCodes
import logic.rejectField
import kotlinx.serialization.Serializable
import logic.QuotaLogic
import logic.QuotaRechargeLogic
import neton.core.annotations.*
import neton.core.http.HttpException
import neton.core.http.NetonErrorCode
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
 * 充值单的回读快照。
 *
 * 刻意**不含**两列：`payOrderId` 是 payment 那张表的内部主键，对用户没有意义，回给前端
 * 只是多一个能被拿去别处试探的 id；`payChannelCode` 是用户自己下单时填的
 * （[RechargeRequest.channelCode]），回读时再说一遍不产生信息。VO 是这里唯一的出口闸 ——
 * 只有列出来的字段会离开，新增字段要过一遍「用户拿它做什么」这个问题。
 */
@Serializable
data class RechargeStatusVO(
    val rechargeId: Long,
    /** 商户订单号 `gateway:quota:{id}`。找客服对账时报这个 —— 它是与 payment 关联的唯一键。 */
    val merchantOrderId: String,
    /** 会入账的网关额度 μUSD。下单那一刻按汇率算定，之后不再变：改汇率不追溯已下的单。 */
    val amountMicro: Long,
    /** 实收法币金额，单位同 payment 的 pay_orders.price。 */
    val price: Long,
    /**
     * 0=待支付 1=已入账 2=已关闭（下单失败时由服务端关掉，见 QuotaRechargeLogic.close）。
     *
     * 这三态正是本 VO 存在的理由：轮询余额只能看到「没变」，而「没变」对应三种完全不同的
     * 处置 —— 待支付要等、已关闭要重新下单、回调没落要找运营。
     */
    val payStatus: Int,
    /** 入账时刻（毫秒）。待支付与已关闭时为 null。 */
    val paidAt: Long? = null,
    val createdAt: Long? = null,
)

/**
 * 额度充值。
 *
 * 真实充值见 [create]：payment 下单 → 支付回调 → 装配层监听者按订单号入账。
 * 钱包是法币账本、网关额度是 μUSD 账本，两者通过订单号关联而非共用余额。
 * 用户送出站付款之后靠 [status] 回读结果 —— 到账不在这个请求里发生，所以必须有个地方能问。
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
            errors += ValidationError("price", GatewayErrorCodes.RECHARGE_PRICE_INVALID, GatewayErrorCodes.RECHARGE_PRICE_INVALID)
        }
        if (request.channelCode.isNullOrBlank()) {
            // 必须是可用的支付渠道码：payment 没有默认渠道
            errors += ValidationError("channelCode", GatewayErrorCodes.RECHARGE_CHANNEL_REQUIRED, GatewayErrorCodes.RECHARGE_CHANNEL_REQUIRED)
        }
        if (errors.isNotEmpty()) throw ValidationException(errors, GatewayErrorCodes.RECHARGE_INVALID)

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

    /**
     * 回读一张充值单的状态 —— 跳转式支付的**完成信号**，此前没有。
     *
     * [create] 在 displayMode=REDIRECT_URL 时把用户送出本站，回来后这个标签页不知道回调落了
     * 没有。旧的唯一信号是轮询 usage/balance，而余额不动分不清「还在等 / 回调失败 / 单子已被
     * 关掉」，三者的处置分别是等、找运营、重新下单。
     *
     * 不是自己的单一律 404，与真的不存在同形，理由见 [logic.QuotaRechargeLogic.visibleTo]。
     *
     * 没有 @Permission：/app 组按 identity 隔离，权限串是管理端的概念 —— 这里加一个反而会被
     * harness S49 要求补一条 system_menus 种子（那条断言是「凡 @Permission 必有菜单入口」）。
     */
    @Get("/get/{rechargeId}")
    suspend fun status(identity: Identity, @PathVariable rechargeId: Long): RechargeStatusVO {
        val r = rechargeLogic.findForUser(rechargeId, identity.id.toLong())
            // 「不存在」与「不是你的」共用同一个机器码，不泄露别人的单是否存在（harness S53 块 D 比对两者）。
            ?: throw HttpException(NetonErrorCode.RESOURCE_NOT_FOUND, GatewayErrorCodes.RECHARGE_NOT_FOUND)
        return RechargeStatusVO(
            rechargeId = r.id,
            // merchant_order_id 不落库，两处都按同一规则现算（markPaid 认单也是这么算的）
            merchantOrderId = QuotaRechargeLogic.merchantOrderId(r.id),
            amountMicro = r.amountMicro,
            price = r.price,
            payStatus = r.payStatus,
            paidAt = r.paidAt,
            createdAt = r.createdAt,
        )
    }

    @Post("/mock")
    suspend fun mock(identity: Identity, @Body request: MockRechargeRequest): RechargeResultVO {
        if (!mockEnabled()) {
            // 测试环境设 NEWGATE_ENABLE_MOCK_RECHARGE=true 才开
            throw HttpException(NetonErrorCode.OPERATION_NOT_ALLOWED, GatewayErrorCodes.MOCK_RECHARGE_DISABLED)
        }
        if (request.amount <= 0) {
            rejectField("amount", GatewayErrorCodes.RECHARGE_AMOUNT_INVALID)
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
