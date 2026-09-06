package model

import kotlinx.serialization.Serializable
import neton.database.annotations.*

/**
 * 兑换码：运营批量生成 → 线下销售 / 活动发放 / 客诉补偿 → 用户自助兑换成网关额度（μUSD）。
 *
 * 台账 [QuotaTransaction] 从 V001 起就在注释里预留了 `type='redeem'` 与「ref = 兑换码」，
 * 但本模型出现之前没有任何代码会产生那种记录。入账形态见 [logic.RedemptionCodeLogic.redeem]。
 *
 * **没有 @SoftDelete**，两个理由，第二个关乎钱：
 *  1. 这是钱据，与 [GatewayQuotaRecharge] 同一判断 —— 作废用状态表达，行永远留着；
 *  2. 软删会让作废的码从 `WHERE deleted = 0` 的部分唯一索引里消失，于是**同一个 code 能被
 *     重新生成**，那就是一张码兑两次。所以唯一索引 [uk_gateway_redemption_code] 是全表唯一。
 *
 * [code] 存**明文**而非哈希：运营要能把码导出发给客户、要在后台反复查看某场活动的码，
 * 只存哈希这两件事都做不了。代价是库泄露即额度泄露，靠码体熵兜住（20 字符 / 31 字符集
 * ≈ 99 bit，见 [logic.RedemptionCodeLogic.ALPHABET]），并且兑换失败一律不回面额。
 */
@Serializable
@Table("gateway_redemption_codes")
data class RedemptionCode(
    @Id val id: Long = 0,
    /** 用户兑换时输入的码体。比对前先归一化（去空白与连字符、转大写），见 logic。 */
    val code: String,
    /** 面额 μUSD。生成时由管理员定，兑换时**不接受客户端改写** —— 能填面额就等于自己定价。 */
    val quotaMicro: Long,
    /** 0=未用 1=已用 2=作废。默认未用。 */
    val status: Int = 0,
    val usedUserId: Long? = null,
    val usedAt: Long? = null,
    /** 过期时刻 epoch millis；null = 永不过期。 */
    val expiresAt: Long? = null,
    /** 批次号：一次生成 N 张归一批，便于按批统计核销率与整批作废。 */
    val batchId: String? = null,
    /** 运营备注（活动名 / 客户名）。只进管理端响应，绝不进用户侧。 */
    val note: String? = null,
    /** 生成者（管理员 id）。谁造的码谁负责，客诉时要能追到人。 */
    val createdBy: Long? = null,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)
