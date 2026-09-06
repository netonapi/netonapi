package bridge

import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import logic.QuotaLogic
import neton.core.component.NetonContext
import neton.logging.Logger
import neton.logging.LoggerFactory
import port.MemberInviteRewardEvent
import port.MemberInviteRewardListener

/**
 * 邀请达成 → 发网关额度。member 报事实，这里决定发多少、发给谁。
 *
 * ## 为什么在装配层
 * `member` 不认识额度，`gateway` 不认识邀请 —— 两边都不该为了这个功能长出对对方的编译依赖
 * （与 [PayRechargeOrderBridge]、[GatewayQuotaRechargePaidListener] 同一条边界）。
 * member 早就把扩展点留好了（`port/MemberInviteRewardPort.kt`：事件、监听者、注册表），
 * 只是此前**没有任何地方 bind 过那个注册表**，于是 `dispatchInviteReward` 恒无可迭代 ——
 * 邀请被如实记录、邀请人的累计数也在维护，但一份奖励都发不出去，而且没有一行日志说明这件事
 * （契约把「未装配」当合法配置，是有意沉默的）。这个桥补的就是那一次 bind。
 *
 * ## 默认不发
 * 两个额度都从环境变量读，**未配置 = 0 = 不奖励**。默认给正值的话，所有既有部署升级即开始
 * 凭空发额度 —— 发钱的路径绝不能因为一次升级就自己打开。运营要开，必须显式配置。
 *
 * ## 幂等
 * 契约明说奖励失败不回滚、监听者可能被再次触发，所以幂等只能来自台账的 ref 唯一约束
 * （`uk_gateway_quota_tx_ref`）。**两个角色必须两个不同的 ref**：同一条邀请记录上有邀请人和
 * 被邀请人两笔，共用一个 ref 的话第二笔会撞上第一笔的哨兵行，被 [QuotaLogic.debitInTx]
 * 当成「已结算」静默跳过 —— 其中一方永远收不到，而且日志里只有一条 idempotent skip。
 *
 * 两笔发放**不包在一个事务里**，这是有意的：包在一起就得选一边失败两边都回滚，而回滚掉的那一边
 * 没有重试机会（`dispatchInviteReward` 每次绑定只跑一次）。分开走则是收敛的 —— 成功的一方由 ref
 * 钉住不会重发，失败的一方重放整个事件也不会重复入账。重放入口是 member 管理端的
 * `POST /admin/member/invite-code/retry-invite-reward/{recordId}`（与既有的 retry-auto-friend 同款）。
 *
 * ## 已知边界：多账号刷邀请
 * member 挡住了「绑自己的码」（补填路径 `INVITE_CODE_SELF`；注册路径因为 `buildMember()` 在
 * `validate()` 之后、注册者此刻还不存在，构造上不可能自荐）。但**同一个人注册多个账号互邀**
 * 挡不住 —— 那需要设备/IP 指纹与人工审核，不是这里能解决的。所以额度默认 0，运营开启前应当
 * 知道自己是在一个可被刷的通道上发钱，并配合 `totalInvited`（事件里带着）设阈值告警。
 *
 * ## 为什么 ctx 要后挂
 * `bind(MemberInviteRewardRegistry::class, ...)` 在 DSL 阶段收集、于 `Neton.kt:298` 执行，
 * 模块初始化在 `:320` —— bind 时 [QuotaLogic] 还不存在。而 member 的 `MemberRuntimeBootstrap`
 * 恰恰在 `:320` 那一步用 `getOrNull` 把注册表读走，所以注册表**必须**在 bind 阶段就备好。
 * 解法与 [PayRechargeOrderBridge] 一样：bind 一个还没挂 ctx 的实例，注册表按引用持有它，
 * `onStart` 里再 [attach]。`onInviteReward` 只在 HTTP 邀请事件里被调，远晚于 onStart。
 */
class GatewayInviteRewardBridge : MemberInviteRewardListener {

    private var quota: QuotaLogic? = null
    private var log: Logger? = null

    /** 由应用的 `onStart` 块调用；此时模块已初始化完毕，QuotaLogic 取得到。 */
    fun attach(ctx: NetonContext) {
        val l = ctx.get(LoggerFactory::class).get("bridge.gateway-invite-reward")
        quota = ctx.get(QuotaLogic::class)
        log = l
        // 启动时把生效的额度念一遍：这是运营唯一能确认「奖励到底开没开、开了多少」的地方。
        // 未装配时整条链路是静默的（契约如此），装配了却配置非法也得在这里就响，而不是等第一个用户绑码。
        val inviter = parseAmount(neton.core.config.getEnv(ENV_INVITER))
        val invitee = parseAmount(neton.core.config.getEnv(ENV_INVITEE))
        l.info(
            "invite reward attached inviter=${inviter ?: "INVALID"} invitee=${invitee ?: "INVALID"} " +
                "(μUSD; 0 = 该角色不奖励; INVALID = 配置非法，该角色不会发奖)",
            mapOf("ts" to Clock.System.now().toEpochMilliseconds()),
        )
    }

    override suspend fun onInviteReward(event: MemberInviteRewardEvent) {
        val q = quota ?: return
        val l = log ?: return
        val inviterAmount = parseAmount(neton.core.config.getEnv(ENV_INVITER))
        val inviteeAmount = parseAmount(neton.core.config.getEnv(ENV_INVITEE))

        // 纵深防御：member 保证邀请人 ≠ 被邀请人，但这是发钱的路径，不能只靠上游的约束。
        // 两者相同时两个 ref 都指向同一个账户 → 同一人拿双份，所以整条记录直接跳过并记 ERROR，
        // 让 member 侧万一回归时是「响着不发」而不是「静默双发」。
        if (event.inviterUserId == event.inviteeUserId) {
            l.error(
                "invite reward SKIPPED: inviter and invitee are the same user " +
                    "user=${event.inviterUserId} recordId=${event.recordId} — 不发奖，需查 member 侧自荐防护是否回归",
                mapOf("recordId" to event.recordId),
            )
            return
        }

        grantRole(q, l, event, event.inviterUserId, inviterAmount, ROLE_INVITER)
        grantRole(q, l, event, event.inviteeUserId, inviteeAmount, ROLE_INVITEE)
    }

    /**
     * 发一个角色的奖励。**不往上抛**：`dispatchInviteReward` 会捕获并记 warn 后继续下一个监听者，
     * 但那样本监听者里另一个角色就再也不会执行 —— 邀请人这边出问题不该连累被邀请人。
     *
     * 代价是失败被吞在这里，所以必须记 ERROR 且带上 ref：这条链路**没有自动重试**
     * （`dispatchInviteReward` 每次绑定只跑一次）。运维用 member 管理端的
     * `retry-invite-reward/{recordId}` 重放整条记录 —— 已成功的那个角色会被 ref 唯一约束挡掉，
     * 只有失败的那个真入账，所以重放是幂等安全的。这也是日志必须带 ref 的原因：对账时按 ref
     * 去 gateway_quota_transactions 查，才知道该不该重放。
     */
    private suspend fun grantRole(
        q: QuotaLogic,
        l: Logger,
        event: MemberInviteRewardEvent,
        userId: Long,
        amount: Long?,
        role: String,
    ) {
        if (amount == null) {
            // 配置非法**不等于** 0。把 `1000usd`、`-5`、`1e3` 静默读成「不奖励」的后果是
            // 运营以为活动已上线、客服按活动口径答复用户，而账上什么都没有 —— 那比不发奖励糟得多。
            l.error(
                "invite reward MISCONFIGURED role=$role env=${envName(role)} " +
                    "raw=${neton.core.config.getEnv(envName(role))} recordId=${event.recordId} — " +
                    "该角色不发奖；必须是十进制非负整数（μUSD）",
                mapOf("recordId" to event.recordId, "role" to role),
            )
            return
        }
        if (amount == 0L) {
            // 记一行而不是安静返回。这一行是「链路装配了但运营没配额度」与「链路压根没装配」
            // 唯一的区分点 —— 两者的账面结果完全一样（零台账、零额度账户），而客服要回答的恰恰是
            // 「我邀请了朋友，为什么两个人都没到账」。attach 那行启动日志只能回答「配了多少」，
            // 回答不了「这一次邀请到底有没有被评估过」—— 注册表没 bind 时 attach 照样会跑照样会打日志。
            // 邀请是低频事件（不是每请求），一次两行不构成噪声。
            l.info(
                "invite reward skipped role=$role amount=0（未配置或显式配为 0，该角色不发奖）" +
                    " recordId=${event.recordId} user=$userId",
                mapOf("recordId" to event.recordId, "role" to role),
            )
            return
        }
        val ref = refFor(event.recordId, role)
        try {
            val balance = q.grant(userId, amount, type = TX_TYPE, ref = ref)
            l.info(
                "invite reward granted role=$role user=$userId amount=$amount balance=$balance " +
                    "ref=$ref totalInvited=${event.totalInvited}",
                mapOf("recordId" to event.recordId, "ref" to ref),
            )
        } catch (e: CancellationException) {
            throw e // 协程取消必须原样上抛
        } catch (e: Throwable) {
            l.error(
                "invite reward FAILED role=$role user=$userId amount=$amount ref=$ref " +
                    "recordId=${event.recordId} — 无自动重试；补发用 POST /admin/member/invite-code/" +
                    "retry-invite-reward/${event.recordId}（重放整条记录，已成功的那个角色会被 ref " +
                    "唯一约束挡掉，所以补发不会重复入账）",
                mapOf("recordId" to event.recordId, "ref" to ref),
                cause = e,
            )
        }
    }

    private fun envName(role: String): String =
        if (role == ROLE_INVITER) ENV_INVITER else ENV_INVITEE

    companion object {
        /** 邀请人奖励（μUSD）。未配置 = 0 = 不奖励。 */
        const val ENV_INVITER = "NEWGATE_INVITE_REWARD_INVITER"

        /** 被邀请人奖励（μUSD）。未配置 = 0 = 不奖励。 */
        const val ENV_INVITEE = "NEWGATE_INVITE_REWARD_INVITEE"

        /**
         * 台账类型。[model.QuotaTransaction] 的 KDoc 原本只列 grant/consume/refund/redeem，
         * 这里加了 invite —— 与当初加 redeem 同一做法。没有任何代码按 type 字面量分支，
         * 但运营要能一眼把「邀请发出去的额度」从手工 grant 里分出来核算获客成本。
         */
        const val TX_TYPE = "invite"

        const val REF_PREFIX = "invite:"
        const val ROLE_INVITER = "inviter"
        const val ROLE_INVITEE = "invitee"

        /**
         * 环境变量 → μUSD 额度。
         *
         * `null` 表示**配置非法**，与 0（未配置 = 不奖励）严格区分：两者在账务上是完全不同的两件事，
         * 一个是「运营决定不发」，一个是「运营想发但写错了」，后者必须响。
         * 只接受十进制非负整数 —— 不收 `1e3`、不收小数、不收带单位的串，因为额度单位是 μUSD，
         * 一个被误读成元/分/毫的配置会直接变成几十倍上百倍的超发。
         */
        fun parseAmount(raw: String?): Long? {
            val t = raw?.trim()
            if (t.isNullOrEmpty()) return 0L
            val v = t.toLongOrNull() ?: return null
            return if (v < 0L) null else v
        }

        /** 一条邀请记录上的两个角色各占一个 ref 命名空间；见类注释「幂等」段。 */
        fun refFor(recordId: Long, role: String): String = REF_PREFIX + recordId + ":" + role
    }
}
