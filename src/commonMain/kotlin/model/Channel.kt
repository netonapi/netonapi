package model

import kotlinx.serialization.Serializable
import neton.database.annotations.*

/** 上游渠道。type: openai_compatible / anthropic / gemini / azure_openai（spec 决策 D）。 */
@Serializable
@Table("gateway_channels")
data class Channel(
    @Id val id: Long = 0,
    val name: String,
    val type: String,
    val baseUrl: String,
    /** 渠道分组（与用户分组求交集决定可用性），逗号分隔如 "default,vip" */
    val groups: String = "default",
    /** 支持的模型清单，逗号分隔 */
    val models: String = "",
    /** 模型映射 JSON：{"请求名":"上游名"} */
    val modelMapping: String? = null,
    /** 请求参数覆盖 JSON */
    val paramOverride: String? = null,
    val priority: Int = 0,
    val weight: Int = 1,
    /** 1=启用 0=禁用 2=自动挂起（全部 Key 失效） */
    val status: Int = 1,
    /** TTFB 超时 ms */
    val ttfbTimeoutMs: Long = 30_000,
    /** 流式块间空闲超时 ms */
    val idleTimeoutMs: Long = 90_000,
    val proxyUrl: String? = null,
    /** 429 冷却截止（epoch ms）：此刻之前该渠道不参与候选，避免立即重试被限流的上游 */
    val cooldownUntil: Long = 0,
    /** 成本折扣（如 "0.6" = 官方价 6 折），DECIMAL 字符串，毛利核算用（spec 决策 B2） */
    val costDiscount: String = "1.0",
    val remark: String? = null,
    @SoftDelete val deleted: Int = 0,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)
