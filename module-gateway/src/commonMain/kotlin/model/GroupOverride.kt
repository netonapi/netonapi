package model

import kotlinx.serialization.Serializable
import neton.database.annotations.*

/**
 * 用户级计费组例外（大客户谈判价）。
 *
 * 主键就是 [userId]：「一个用户最多一条例外」是业务不变量本身，拿它当主键比
 * 「自增 id + user_id 唯一索引」少一处可能不一致的地方。框架通过
 * `@Id(autoGenerate = false)` 支持自然主键（见 neton-ksp 的 EntityTableProcessor
 * 与 neton-database 的 GeneratedIdInsertContractTest）。
 *
 * 为什么放 gateway 而不是 member：这纯粹是计费语义。member_groups 只有
 * name/remark/status，把倍率或例外塞进 member 会污染所有使用 member 的产品。
 *
 * ⚠️ 这张表**只能由管理端写**，绝不能出现在 app 侧接口里 —— 自助用户能改自己的
 * 计费组，就等于自己给自己打折。
 */
@Serializable
@Table("gateway_group_overrides")
data class GroupOverride(
    @Id(autoGenerate = false) val userId: Long,
    /** 指向 [GatewayGroup.code]（稳定身份，不是可改的显示名） */
    val groupCode: String,
    val remark: String? = null,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)
