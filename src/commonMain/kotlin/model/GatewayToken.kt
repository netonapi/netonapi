package model

import kotlinx.serialization.Serializable
import neton.database.annotations.*

/** 用户 API 令牌。明文不落库：key_hash = SHA-256(sk-...)，key_display = 前7+后4。 */
@Serializable
@Table("gateway_tokens")
data class GatewayToken(
    @Id val id: Long = 0,
    val userId: Long,
    val name: String,
    val keyHash: String,
    val keyDisplay: String,
    /** μUSD 预算；null = 不限（仍受账户余额约束） */
    val quotaBudget: Long? = null,
    val quotaUsed: Long = 0,
    /** 已预留（在途请求占位）μUSD；可用预算 = quotaBudget - quotaUsed - quotaReserved */
    val quotaReserved: Long = 0,
    val expiresAt: Long? = null,
    /** 允许模型，逗号分隔；空 = 不限 */
    val allowedModels: String = "",
    /** 允许 IP（CIDR/精确），逗号分隔；空 = 不限 */
    val allowedIps: String = "",
    /** 覆盖用户分组；空 = 用用户自身分组 */
    val groupOverride: String? = null,
    val status: Int = 1,
    @SoftDelete val deleted: Int = 0,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)
