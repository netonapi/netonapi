package model

import kotlinx.serialization.Serializable
import neton.database.annotations.*

/**
 * 计费分组。
 *
 * [code] 是**稳定身份**：路由（[Channel.groups] 的 CSV）、Key 覆盖
 * （[GatewayToken.groupOverride]）、计价倍率三处都按它匹配，创建后不可改。
 * [name] 只是显示名，随时可改、改不动身份。
 *
 * 为什么要把身份和显示名分开：此前只有 name 且无唯一约束，管理端可以自由改名与
 * 删除，而改名会让路由静默失配、倍率静默回落到 1.0。前者至少报错（调不通），
 * 后者不报错 —— 表现为照原价扣费，在预付费模式里就是静默少收，只能靠事后查账发现。
 * 详见 sql/postgresql/V002__group_billing_identity.sql。
 *
 * [memberGroupId] 是到 module-member 会员组的映射：**归属关系在 member、倍率在这里**。
 * member_groups 只有 name/remark/status，没有任何金钱语义，把 ratio 放进 member 会
 * 污染所有使用 member 的产品；反过来在本模块另建一套用户归属则会与 member 双写。
 * 一个会员组最多映射到一个计费组（DB 唯一索引 + 管理端查重），未映射的为 null。
 */
@Serializable
@Table("gateway_groups")
data class GatewayGroup(
    @Id val id: Long = 0,
    val code: String,
    val name: String,
    /** 倍率 DECIMAL 字符串，如 "1.0"/"0.8" */
    val ratio: String = "1.0",
    /** 对外说明（用户建 Key 选分组时看到）。 */
    val description: String? = null,
    val memberGroupId: Long? = null,
    /** 1 = 所有用户建 Key 时都能选；0 = 只有运营单独开给的用户（用户例外 / 会员组映射）能选。default 组永远可选。 */
    val selectable: Int = 0,
    @SoftDelete val deleted: Int = 0,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)
