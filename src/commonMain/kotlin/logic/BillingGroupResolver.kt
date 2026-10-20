package logic

import kotlinx.coroutines.CancellationException
import model.GatewayGroup
import model.GroupOverride
import neton.database.dsl.*
import table.GatewayGroupTable
import table.GroupOverrideTable

/**
 * 会员分组 → 计费组的 SPI。
 *
 * gateway 不认识 member（跨模块协作走装配层，不在编译期连起来），所以这里只问一件事：
 * 「这个用户属于哪个会员组」。装配层实现它（读 member 的 groupId）并 bind 进 NetonContext。
 *
 * 返回 null = 该用户没有所属会员组（正常情形，继续往 default 组走）。
 * **抛异常 = 解析失败，不得表达成 null** —— 否则「会员系统坏了」与「用户没有分组」成了同一件事，
 * 前者会静默按 default 组计价，正是这批改动要消灭的形态。
 */
interface MemberGroupPort {
    suspend fun memberGroupId(userId: Long): Long?
}

/** 这个组是哪一层给的。进日志：错账排查第一个要问的就是来源。 */
enum class GroupSource { TOKEN_OVERRIDE, USER_OVERRIDE, MEMBER_GROUP, DEFAULT }

/**
 * 拒绝原因。三者的处置方与响应码都不同，不能一律 400：
 *  - [CONFIG_BROKEN] 运营事故（兜底组缺失、令牌/用户级例外指向已删的组）→ 500，且调用方**必须 ERROR 告警**。
 *  - [NOT_ALLOWED] 部署决策（会员有所属分组，但没给它配计费组）→ 403，客户端重试无意义。
 *  - [RESOLUTION_FAILED] 依赖方（会员系统 / DB）解析时抛错 → 500，且**绝不回落**。
 *
 * 这两个 5xx 与 [RelayEngine] 里 `no_candidate` 的 404 看着像两套标准，其实是同一条的两面：
 * **5xx 只在「有人会被叫醒去修」时才许用**。
 *  - `no_candidate`（模型没配 / 能力没声明）→ 404：请求指向一个我们不提供的东西，404 是关于
 *    「客户端要什么」的真实陈述，我方没有故障要告警；回 503 只会让客户端对着不变的配置一直重试。
 *  - [CONFIG_BROKEN] → 500：请求完全合法，坏的是我方配置，回 4xx 等于把责任推给客户端。
 *    代价同样是客户端会重试撞墙 —— 所以告警不是可选项，它是这个 500 成立的**前提**。
 *
 * 告警只能落在调用方的日志级别上：拒绝走 `respondError` / 直接写响应，**不抛异常**，因此框架那条
 * 「5xx → ERROR 带 cause + 落 infra_api_error_logs」的路径对这里不可达（见 BufferedHttpDispatcher）。
 * 于是两个消费点（RelayEngine、ModelsController）都按 `httpStatus >= 500` 把日志升到 ERROR。
 */
enum class GroupReject(val httpStatus: Int, val code: String) {
    CONFIG_BROKEN(500, "billing_group_config_broken"),
    NOT_ALLOWED(403, "billing_group_not_allowed"),
    RESOLUTION_FAILED(500, "billing_group_resolution_failed"),
}

sealed interface GroupResolution {
    /** [group] 同时供路由（按 [GatewayGroup.code] 匹配渠道）与计价（[GatewayGroup.ratio]）使用。 */
    data class Resolved(val group: GatewayGroup, val source: GroupSource) : GroupResolution

    data class Rejected(val reason: GroupReject, val message: String) : GroupResolution
}

/**
 * 计费组解析器：路由、令牌覆盖、计价必须解析到**同一个组**。
 *
 * 顺序（前一层给出结果就停）：
 *  1. 令牌级覆盖 `gateway_tokens.group_override` —— 管理端设置，自助用户改不了；
 *  2. 用户级例外 `gateway_group_overrides` —— 大客户谈判价；
 *  3. 会员分组映射 `gateway_groups.member_group_id`（需装配层绑定 [MemberGroupPort]）；
 *  4. `default` 组。
 *
 * 每一层命中都必须对应一行**活着**的 gateway_groups：引用悬空即拒绝，不回落。
 * 此前的实现是 `?: "default"` 与 `?: "1.0"` 两级静默回落 —— 组被改名或删掉时请求照常放行、
 * 倍率悄悄变 1.0，钱少收而日志里一行痕迹都没有。
 */
object BillingGroupResolver {
    /** 兜底组的 code。它必须真实存在；不存在就是配置损坏，而不是「那就按 1.0 收」。 */
    const val DEFAULT_CODE = "default"

    /** 会员分组集成的开关。开着却没绑定 [MemberGroupPort] → 启动失败（见 GatewayRuntimeBootstrap）。 */
    const val ENV_MEMBER_GROUP_BILLING = "NETONAPI_MEMBER_GROUP_BILLING"

    /** 装配期可变、服务期只读（与 RelayEngine.guardedClients 同一套约定）。 */
    var memberPort: MemberGroupPort? = null

    suspend fun resolve(userId: Long, tokenGroupOverride: String?): GroupResolution {
        // 1) 令牌级覆盖
        val override = tokenGroupOverride?.trim()?.takeIf { it.isNotEmpty() }
        if (override != null) {
            return byCode(override, GroupSource.TOKEN_OVERRIDE) ?: GroupResolution.Rejected(
                GroupReject.CONFIG_BROKEN,
                "token group override '$override' has no live billing group",
            )
        }

        // 2) 用户级例外
        val userOverride = GroupOverrideTable.oneWhere { GroupOverride::userId eq userId }
        if (userOverride != null) {
            return byCode(userOverride.groupCode, GroupSource.USER_OVERRIDE) ?: GroupResolution.Rejected(
                GroupReject.CONFIG_BROKEN,
                "per-user billing override for user=$userId points at '${userOverride.groupCode}', " +
                    "which has no live billing group",
            )
        }

        // 3) 会员分组映射
        val port = memberPort
        if (port != null) {
            val memberGroupId = try {
                port.memberGroupId(userId)
            } catch (e: CancellationException) {
                throw e // 协程取消必须原样上抛，吞掉会让取消语义失效
            } catch (e: Throwable) {
                return GroupResolution.Rejected(
                    GroupReject.RESOLUTION_FAILED,
                    "member group lookup failed for user=$userId: ${e.message}",
                )
            }
            if (memberGroupId != null) {
                val mapped = GatewayGroupTable.oneWhere { GatewayGroup::memberGroupId eq memberGroupId }
                return if (mapped != null) {
                    GroupResolution.Resolved(mapped, GroupSource.MEMBER_GROUP)
                } else {
                    GroupResolution.Rejected(
                        GroupReject.NOT_ALLOWED,
                        "member group $memberGroupId has no billing group mapped; " +
                            "user=$userId is not allowed to relay",
                    )
                }
            }
        }

        // 4) 兜底组：必须存在
        return byCode(DEFAULT_CODE, GroupSource.DEFAULT) ?: GroupResolution.Rejected(
            GroupReject.CONFIG_BROKEN,
            "no live billing group with code '$DEFAULT_CODE'; every request without an override needs it",
        )
    }

    private suspend fun byCode(code: String, source: GroupSource): GroupResolution.Resolved? =
        GatewayGroupTable.oneWhere { GatewayGroup::code eq code }
            ?.let { GroupResolution.Resolved(it, source) }
}
