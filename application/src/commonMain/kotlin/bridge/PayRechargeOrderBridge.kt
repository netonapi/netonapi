package bridge

import logic.PayOrderLogic
import logic.RechargeOrderPort
import logic.RechargePrepay
import model.PayOrder
import neton.core.component.NetonContext

/**
 * [RechargeOrderPort] 的 payment 实现。
 *
 * gateway 只说「要收多少钱、商户订单号是什么」，由这里翻译成 payment 的下单调用 ——
 * 于是 gateway 编译期不认识 payment，payment 也不认识网关额度语义。
 *
 * ⚠️ 商户订单号一律用**调用方给的**那个（`gateway:quota:{rechargeId}`）：
 * 支付成功事件回来时，gateway 只凭这个号认单（见 [GatewayQuotaRechargePaidListener]）。
 * 这里另编一个的话，钱收了但额度永远不入账，而且不会有任何报错。
 *
 * ## 为什么 ctx 要后挂
 * `bind(RechargeOrderPort::class, ...)` 在 DSL 阶段收集、于 `Neton.kt:298` 执行，
 * 而模块初始化在 `:320` —— bind 时 `PayOrderLogic` 还不存在，构造期注入拿不到它。
 * 所以由 Main.kt 的 `onStart`（`:333`）调 [attach]；[createOrder] 只在 HTTP 请求中被调用，
 * 远晚于 onStart。真没挂上就明确报错，不静默返回一个假的下单结果。
 */
class PayRechargeOrderBridge : RechargeOrderPort {

    private var ctx: NetonContext? = null

    /** 由应用的 `onStart` 块调用；此时模块已初始化完毕。 */
    fun attach(ctx: NetonContext) {
        this.ctx = ctx
    }

    override suspend fun createOrder(
        merchantOrderId: String,
        subject: String,
        price: Long,
        channelCode: String?,
        userIp: String?,
    ): RechargePrepay {
        val payOrders = (ctx ?: error(
            "PayRechargeOrderBridge is not attached to a NetonContext. Call attach(ctx) from the " +
                "application's onStart block: bind() runs before module initialization, so PayOrderLogic " +
                "does not exist yet at bind time.",
        )).get(PayOrderLogic::class)

        val prepay = payOrders.submit(
            PayOrder(
                merchantOrderId = merchantOrderId,
                subject = subject,
                price = price,
                channelCode = channelCode,
                userIp = userIp,
                // 不设就永不过期：单子一直挂在待支付，对账任务每十分钟去平台查一遍。
                // 支付宝下单会带上同一时刻作为 time_expire，过期后收银台也不让付了。
                expireTime = kotlin.time.Clock.System.now().toEpochMilliseconds() + ORDER_TTL_MILLIS,
            )
        )
        // displayMode 传枚举名而不是枚举本身：gateway 不 import payment 的 PayDisplayMode。
        // 代价是前端按字符串分支、多一个枚举值时走默认分支 —— 那是前端该处理的展示降级，
        // 比让 gateway 沾上 payment 的类型便宜。
        return RechargePrepay(
            orderId = prepay.orderId,
            displayMode = prepay.displayMode.name,
            payload = prepay.payload,
            channelOrderNo = prepay.channelOrderNo,
        )
    }

    companion object {
        /** 充值单有效期：够扫码付款，又不至于让废单长期占着对账。 */
        const val ORDER_TTL_MILLIS = 30 * 60 * 1000L
    }
}
