package model

import kotlinx.serialization.Serializable
import neton.database.annotations.*

/**
 * 渠道 × 请求模型的实际成本价（V020）。[model] 是请求模型名（对外模型），不是上游的模型名。
 * 价格单位同 [ModelPrice]（USD / 1M tokens；按次为 μUSD）。[stale] = 1：上游价目里已经没有这个模型，保留的是旧值。
 */
@Serializable
@Table("gateway_channel_model_costs")
data class ChannelModelCost(
    @Id val id: Long = 0,
    val channelId: Long,
    val model: String,
    val inputPrice: String,
    val outputPrice: String,
    val cacheReadPrice: String = "0",
    val cacheWritePrice: String = "0",
    val perRequestPrice: Long? = null,
    val tiers: String? = null,
    /** upstream / manual */
    val source: String,
    val stale: Int = 0,
    val updatedAt: Long = 0,
)
