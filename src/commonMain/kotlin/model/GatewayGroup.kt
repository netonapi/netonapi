package model

import kotlinx.serialization.Serializable
import neton.database.annotations.*

/** 用户分组倍率。 */
@Serializable
@Table("gateway_groups")
data class GatewayGroup(
    @Id val id: Long = 0,
    val name: String,
    /** 倍率 DECIMAL 字符串，如 "1.0"/"0.8" */
    val ratio: String = "1.0",
    val description: String? = null,
    @SoftDelete val deleted: Int = 0,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)
