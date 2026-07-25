package model

import kotlinx.serialization.Serializable
import neton.database.annotations.*

/** 额度账户（μUSD）。version 用于乐观锁条件更新防并发超扣。 */
@Serializable
@Table("gateway_quota_accounts")
data class QuotaAccount(
    @Id val id: Long = 0,
    val userId: Long,
    val balance: Long = 0,
    /** 已预留（在途请求占位）μUSD；可用余额 = balance - reservedBalance */
    val reservedBalance: Long = 0,
    val version: Long = 0,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)
