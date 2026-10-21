package model

import kotlinx.serialization.Serializable
import neton.database.annotations.*

/** 模型定价（官方价 + 售价覆盖，spec 决策 B2）。价格 DECIMAL 字符串，USD/1M tokens。 */
@Serializable
@Table("gateway_model_prices")
data class ModelPrice(
    @Id val id: Long = 0,
    val model: String,
    val inputPrice: String = "0",
    val outputPrice: String = "0",
    val cacheReadPrice: String = "0",
    val cacheWritePrice: String = "0",
    /** 按次计价（μUSD/次）；非 null 时 token 价格无效 */
    val perRequestPrice: Long? = null,
    /** reserve 输出上限：请求未传 max_tokens 时用此值预留；null 且请求未传 → 拒绝请求 */
    val defaultMaxOutputTokens: Int? = null,
    /** 售价覆盖 JSON（空 = 官方价 × 全局加价率） */
    val saleOverride: String? = null,
    /** 按上下文长度分档的官方价（JSON，见 [logic.PricingTiers]）；空 = 单档。 */
    val tiers: String? = null,
    /** 价源：builtin/manual（litellm/openrouter M3 加入） */
    val source: String = "manual",
    @SoftDelete val deleted: Int = 0,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)
