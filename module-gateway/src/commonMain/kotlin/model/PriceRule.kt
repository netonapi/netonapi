package model

import kotlinx.serialization.Serializable
import neton.database.annotations.*

/**
 * 定价规则（docs/gateway-pricing-spec.md §4.3）：「分组或用户 × 模型」的一条约定。
 *
 * - [subjectType] `GROUP`（[subjectId] = 计费分组 code）或 `USER`（[subjectId] = 用户 ID）。
 * - [mode] `PLATFORM`：平台价 × 分组倍率，不改价，只用来开放 `RULES_ONLY` 模型；
 *   `OFFICIAL_DISCOUNT`：官方价 × [discount]（逐项、逐档）；`FIXED`：[prices] 固定售价（与 sale_override 同结构，只用于不分档的模型）。
 * - 命中折扣 / 固定价时价格就是约定的数，不再乘分组倍率。
 */
@Serializable
@Table("gateway_price_rules")
data class PriceRule(
    @Id val id: Long = 0,
    val subjectType: String,
    val subjectId: String,
    val model: String,
    val mode: String,
    val discount: String? = null,
    val prices: String? = null,
    val remark: String? = null,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)
