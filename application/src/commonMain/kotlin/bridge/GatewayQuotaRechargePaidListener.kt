package bridge

import event.PayOrderPaidEvent
import logic.QuotaRechargeLogic
import neton.core.event.DeliveryMode
import neton.core.event.DomainEventListener

/**
 * 网关额度充值到账：payment 的「支付成功」→ gateway 按商户订单号入账。
 *
 * 这是同一个 `PayOrderPaidEvent` 的**第三个消费方**（前两个：payment 自己的钱包充值、
 * 内容解锁）。事件总线取代单绑定端口正是为了这个 —— 每来一个业务方就加一个监听者，
 * 谁也不用挤进别人的实现里解析前缀。
 *
 * ## 为什么在装配层
 * 监听 [PayOrderPaidEvent] 必然 import payment 的 event 与 model，那条 gateway→payment
 * 的编译依赖正是 [logic.RechargeOrderPort] 要避免的；而 payment 也不该认识网关额度
 * （它的 `WalletRechargePaidListener` 只管 `wallet:recharge:`）。装配层是唯一同时认识两边的地方，
 * 也是 rbac-spec §4.2「跨模块依赖通过明确注入、不绕过到对方 Table」指的位置。
 *
 * ## SYNC
 * 与 payment 的钱包到账同理：到账必须与支付成功同生共死。异步化会出现「钱收了额度没加」
 * 的窗口，而额度是用户下一次请求就要用的数字。代价是本监听者抛异常会回滚整笔支付 ——
 * 那正是想要的：宁可让用户重付一次，也不要出现付了钱没额度、且没有任何痕迹的账。
 * 前缀过滤让非本单在任何 DB 操作之前就 return，钱包单根本不进网关代码路径。
 *
 * ## 幂等
 * 三层，互相独立：payment 的 `updateSuccess` 按 status 早退（重复回调不二次发布）→
 * [QuotaRechargeLogic.markPaid] 的 pay_status 乐观锁占位 → 台账 ref 唯一约束
 * （ref = merchantOrderId）。任一层失效，另一层兜底。
 */
class GatewayQuotaRechargePaidListener(
    private val recharges: QuotaRechargeLogic,
) : DomainEventListener<PayOrderPaidEvent> {

    override val eventType = PayOrderPaidEvent::class

    /** 稳定标识，落库投递靠它路由回来，**不可更改**（重命名类不影响它）。 */
    override val listenerId = "gateway.quota-recharge-paid"

    override val mode: DeliveryMode = DeliveryMode.SYNC

    override suspend fun onEvent(event: PayOrderPaidEvent) {
        // 只认自己的前缀：钱包充值 / 内容购买各有各的监听者，互不干涉
        val rechargeId = QuotaRechargeLogic.parse(event.order.merchantOrderId) ?: return
        recharges.markPaid(rechargeId, event.order.channelCode ?: "")
    }
}
