package model

import kotlinx.serialization.Serializable
import neton.database.annotations.*

/** 额度台账（审计，只增不改）。type: grant/consume/refund/redeem。 */
@Serializable
@Table("gateway_quota_transactions")
data class QuotaTransaction(
    @Id val id: Long = 0,
    val userId: Long,
    val type: String,
    /** 变动额 μUSD：grant 为正，consume 为负 */
    val amount: Long,
    val balanceAfter: Long,
    /** 关联凭据：支付订单号 / usage_log id / 兑换码 */
    val ref: String? = null,
    @CreatedAt val createdAt: Long? = null,
)
