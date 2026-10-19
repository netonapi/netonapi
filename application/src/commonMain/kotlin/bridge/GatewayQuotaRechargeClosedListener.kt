package bridge

import event.PayOrderClosedEvent
import logic.QuotaRechargeLogic
import neton.core.event.DeliveryMode
import neton.core.event.DomainEventListener

/**
 * 网关额度充值的支付单过期关闭 → 充值单跟着关，用户在充值记录里看到「已关闭」而不是永远「待支付」。
 *
 * 只是状态同步，不涉及钱：关闭后若平台的付款回调晚到，[QuotaRechargeLogic.markPaid] 仍会入账。
 */
class GatewayQuotaRechargeClosedListener(
    private val recharges: QuotaRechargeLogic,
) : DomainEventListener<PayOrderClosedEvent> {

    override val eventType = PayOrderClosedEvent::class

    /** 稳定标识，落库投递靠它路由回来，**不可更改**。 */
    override val listenerId = "gateway.quota-recharge-closed"

    override val mode: DeliveryMode = DeliveryMode.SYNC

    override suspend fun onEvent(event: PayOrderClosedEvent) {
        val rechargeId = QuotaRechargeLogic.parse(event.order.merchantOrderId) ?: return
        recharges.markClosed(rechargeId)
    }
}
