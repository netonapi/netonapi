package model

import kotlinx.serialization.Serializable
import neton.database.annotations.*

/**
 * 额度台账（审计，只增不改）。
 *
 * ## type 取值（下面七种是穷举核实过的全部产出点，别照旧注释猜）
 * 入账（amount 为正）：
 * - `grant`    运营在管理台手动充值 —— [logic.QuotaLogic.grant] 的默认值
 * - `recharge` 真实支付回调入账 —— [logic.QuotaRechargeLogic]
 * - `redeem`   兑换码核销 —— [logic.RedemptionCodeLogic]
 * - `invite`   邀请奖励 —— 产出点**不在本模块**，在装配层的 GatewayInviteRewardBridge
 *   （application/src/commonMain/kotlin/bridge/）。在本模块里 grep "invite" 找不到它，这不是漏了：
 *   member 只上报「邀请达成」这个事实、gateway 只提供 grant，奖励规则由装配层缝合，
 *   于是加一种新奖励零改这两个模块。找 type 的产出点要连装配层一起搜。
 *
 * 扣减（amount 为负）：
 * - `consume`  结算按实际用量扣费 —— [logic.SettlementLogic]
 * - `revoke`   运营撤回已发放的额度 —— [logic.QuotaLogic.revoke]
 * - `refund`   充值被退款，按退款比例扣回 —— [logic.QuotaRechargeLogic.markRefunded]（由装配层监听
 *   payment 的退款事件调用）。可能把余额扣成负数：钱已经退了，额度必须收回。
 *
 * consume 与 revoke 都是负数行，区别在成因：consume 是用户真的用掉了，revoke 是平台把发错的
 * 钱收回来。分开记才能回答「这个客户账上为什么是负的」——混成一种就得去翻请求日志。
 *
 */
@Serializable
@Table("gateway_quota_transactions")
data class QuotaTransaction(
    @Id val id: Long = 0,
    val userId: Long,
    val type: String,
    /**
     * 变动额 μUSD，**带符号**：入账为正（grant/recharge/redeem/invite），扣减为负（consume/revoke/refund）。
     *
     * 撤回接口回给前端的字段叫 `revokedAmount` 且恒正，与此处符号相反。那个不对称是刻意的，
     * 理由写在 [controller.admin.quota.RevokeQuotaResultVO] 的类级 KDoc 里。
     */
    val amount: Long,
    val balanceAfter: Long,
    /**
     * 关联凭据，兼作幂等键：同一个 ref 只入账一次（唯一索引见 [logic.QuotaLogic.debitInTx]）。
     * ref 空间由多个业务共用一个索引，所以每种来源都带前缀防碰撞：
     * `gateway:quota:{rechargeId}`（recharge）、`settlement:{id}`（consume）、`redeem:{code}`、
     * `invite:{recordId}:{inviter|invitee}`、`mock:{hex}`（已删除的模拟充值留下的历史行）、`revoke:{工单号}`（约定，非强制）、
     * `refund:gateway:quota:{rechargeId}`（充值退款扣回）。
     *
     * ⚠️ `redeem:` 行的 ref 里是**完整兑换码明文**（RedemptionCodeLogic 传 REF_PREFIX + code）。
     * 这不是脱敏漏了一处：能产生这种行的码必然已核销、残值为零，而台账查询两端都按 user_id 严格
     * 隔离（用户只看自己的行），拿到它也换不到钱。管理端列表返回的 code 字段是脱敏的（harness S50
     * 锁住），两处别搞混。真正的存储级风险是码在 gateway_redemption_codes 里明文落库，
     * 见 backlog REDEMPTION-CODE-HASH-AT-REST-P2。
     */
    val ref: String? = null,
    @CreatedAt val createdAt: Long? = null,
)
