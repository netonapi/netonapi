package model

import kotlinx.serialization.Serializable
import neton.database.annotations.*

/** 渠道 Key（多 Key 逐个禁用，优于 new-api 换行分隔）。 */
@Serializable
@Table("gateway_channel_keys")
data class ChannelKey(
    @Id val id: Long = 0,
    val channelId: Long,
    val apiKey: String,
    /** 1=可用 0=手动禁用 2=连续失败自动禁用 */
    val status: Int = 1,
    val failCount: Int = 0,
    val lastError: String? = null,
    val lastUsedAt: Long? = null,
    @SoftDelete val deleted: Int = 0,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)
