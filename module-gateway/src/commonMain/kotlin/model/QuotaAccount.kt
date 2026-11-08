package model

import kotlinx.serialization.Serializable

/** 额度账户（μUSD）。version 用于乐观锁条件更新防并发超扣。 */
@Serializable
data class QuotaAccount(
    val id: Long = 0,
    val userId: Long,
    val balance: Long = 0,
    /** 已预留（在途请求占位）μUSD；可用余额 = balance - reservedBalance */
    val reservedBalance: Long = 0,
    val version: Long = 0,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
) {
    companion object {
        fun from(account: ConsumptionAccount) = QuotaAccount(
            account.id, account.userId, account.balance, account.reservedBalance, account.version,
            account.createdAt, account.updatedAt,
        )
    }
}
