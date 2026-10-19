package bridge

import event.PayOrderRefundedEvent
import logic.QuotaRechargeLogic
import neton.core.event.DeliveryMode
import neton.core.event.DomainEventListener

/**
 * 网关额度充值被退款：payment 的「已退款」→ gateway 按退款比例扣回这笔充值发放的额度。
 *
 * 与 [GatewayQuotaRechargePaidListener] 成对：那边钱到账 → 发额度，这边钱退回 → 收额度。
 * 以前只有前一半，管理端退款后钱退给了用户、额度却留在账上。
 *
 * SYNC：扣回与退款同生共死（同一事务）。扣回失败会回滚退款状态，平台侧的钱已经退了的情况下，
 * 订单会停在「已支付」并留下 ERROR，让人工对账 —— 比「退了钱额度还在」且没有痕迹好。
 * 幂等由 [QuotaRechargeLogic.markRefunded] 的状态早退、乐观锁与台账 ref 唯一约束三层保证。
 */
class GatewayQuotaRechargeRefundedListener(
    private val recharges: QuotaRechargeLogic,
) : DomainEventListener<PayOrderRefundedEvent> {

    override val eventType = PayOrderRefundedEvent::class

    /** 稳定标识，落库投递靠它路由回来，**不可更改**。 */
    override val listenerId = "gateway.quota-recharge-refunded"

    override val mode: DeliveryMode = DeliveryMode.SYNC

    override suspend fun onEvent(event: PayOrderRefundedEvent) {
        val rechargeId = QuotaRechargeLogic.parse(event.order.merchantOrderId) ?: return
        recharges.markRefunded(rechargeId, refundPrice = event.refundAmount, orderPrice = event.order.price)
    }
}
