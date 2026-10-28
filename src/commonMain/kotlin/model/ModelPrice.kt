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
    /** 对外展示名（V021），如「Opus 5.5」；空 = 直接显示模型 ID。 */
    val displayName: String? = null,
    /** 价源：builtin/manual（litellm/openrouter M3 加入） */
    val source: String = "manual",
    /**
     * 上架（V023）：1 = 对外提供（用户可见、可调用）；0 = 只在价格库里，用户看不到、调不了。
     * 对外提供哪些模型只由这里决定，上游支持什么不影响（docs/model-catalog-design.md）。
     */
    val listed: Int = 1,
    /** 开放范围（V026）：ALL = 所有用户；RULES_ONLY = 只对有定价规则的用户 / 分组开放（spec §4.3.2）。 */
    val access: String = "ALL",
    @SoftDelete val deleted: Int = 0,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)
