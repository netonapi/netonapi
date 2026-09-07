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
    /**
     * 计费归因（V007）：[charged] 是按哪个组的倍率算出来的、那个组是哪一层给的
     * （[logic.GroupSource] 的名字：TOKEN_OVERRIDE / USER_OVERRIDE / MEMBER_GROUP / DEFAULT）。
     *
     * 没有这两列时「收了多少」查得出而「为什么是这个价」查不出：倍率相同的四层含义完全不同
     * （令牌口子 / 大客户谈判价 / 会员套餐 / 标准价），而 gateway_group_overrides 是硬删，
     * 撤销之后连「这位客户曾经谈过什么价」都无从对证。
     *
     * null = 这一行写在归因落库之前，语义与取舍见 [Settlement.groupCode]。
     */
    val groupCode: String? = null,
    val groupSource: String? = null,
    val ttfbMs: Long? = null,
    val latencyMs: Long? = null,
    /** 1=流式 */
    val stream: Int = 0,
    /** ok/error/partial */
    val status: String = "ok",
    val errorCode: String? = null,
    @CreatedAt val createdAt: Long? = null,
)
