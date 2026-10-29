package model

import kotlinx.serialization.Serializable
import neton.database.annotations.*

/**
 * 网关额度充值意图单。
 *
 * 为什么不复用 payment 的 pay_orders：pay_orders **没有 user_id 列**（订单归属由发起模块自己记），
 * 而且它是法币账本 —— 网关额度是 μUSD 账本。两者通过 merchant_order_id 关联，不共用余额。
 * 形态照 payment 的 pay_wallet_recharges：发起模块自己一行意图单，
 * merchantOrderId = `gateway:quota:{id}`（见 [logic.QuotaRechargeLogic]）。
 *
 * 没有 @SoftDelete：钱据不做软删。要作废就置 [logic.QuotaRechargeLogic.PAY_STATUS_CLOSED]。
 */
@Serializable
@Table("gateway_quota_recharges")
data class GatewayQuotaRecharge(
    @Id val id: Long = 0,
    val userId: Long,
    /** 要入账的网关额度（μUSD）。由服务端按汇率算出，**不接受客户端直接指定**。 */
    val amountMicro: Long,
    /** 要收的法币金额，单位与 payment 的 pay_orders.price 一致。 */
    val price: Long,
    /** 0=待支付 1=已入账 2=已关闭。默认待支付 —— 充值单默认成「已入账」是危险的。 */
    val payStatus: Int = 0,
    val payOrderId: Long? = null,
    val payChannelCode: String? = null,
    val paidAt: Long? = null,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)
