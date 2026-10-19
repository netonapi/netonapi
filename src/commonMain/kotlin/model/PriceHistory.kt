package model

import kotlinx.serialization.Serializable
import neton.database.annotations.*

/** 调价记录（V018）。只增不改：它是事后对账的依据。 */
@Serializable
@Table("gateway_price_history")
data class PriceHistory(
    @Id val id: Long = 0,
    val model: String,
    /** create / update / delete / import / sync / calibrate */
    val action: String,
    /** 变化前的价格字段 JSON；新增时为空。 */
    val before: String? = null,
    /** 变化后的价格字段 JSON；删除时为空。 */
    val after: String? = null,
    /** 后台操作人；定时同步为空。 */
    val operatorId: Long? = null,
    @CreatedAt val createdAt: Long? = null,
)
