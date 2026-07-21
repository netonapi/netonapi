package model

import kotlinx.serialization.Serializable
import neton.database.annotations.*

/** 逐请求用量日志（收入/成本双记，支撑毛利看板，spec 决策 B2）。 */
@Serializable
@Table("gateway_usage_logs")
data class UsageLog(
    @Id val id: Long = 0,
    val userId: Long,
    val tokenId: Long,
    val channelId: Long,
    val requestModel: String,
    val upstreamModel: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    /** 收入 μUSD（按售价） */
    val charged: Long = 0,
    /** 成本 μUSD（官方价 × 渠道折扣） */
    val cost: Long = 0,
    val ttfbMs: Long? = null,
    val latencyMs: Long? = null,
    /** 1=流式 */
    val stream: Int = 0,
    /** ok/error/partial */
    val status: String = "ok",
    val errorCode: String? = null,
    @CreatedAt val createdAt: Long? = null,
)
