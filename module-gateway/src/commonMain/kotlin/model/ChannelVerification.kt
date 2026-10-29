package model

import kotlinx.serialization.Serializable
import neton.database.annotations.CreatedAt
import neton.database.annotations.Id
import neton.database.annotations.Table
import neton.database.annotations.UpdatedAt

/** 渠道能力在真实上游上的验证结果（V016）。 */
@Serializable
@Table("gateway_channel_verifications")
data class ChannelVerification(
    @Id val id: Long = 0,
    val channelId: Long,
    val capability: String,
    val model: String,
    /** 1=通过 0=未通过 */
    val ok: Int,
    val verifiedAt: Long,
    val detail: String? = null,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)
