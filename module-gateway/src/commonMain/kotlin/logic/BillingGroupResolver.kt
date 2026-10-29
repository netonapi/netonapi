package logic

import kotlinx.coroutines.CancellationException
import model.GatewayGroup
import model.GatewayToken
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
 * 用途：会员组映射的计费组是这个用户**可选**的分组之一（见 [BillingGroupResolver.options]）。
 * 返回 null = 该用户没有所属会员组。**抛异常 = 解析失败，不得表达成 null** —— 否则「会员系统坏了」
 * 与「用户没有分组」成了同一件事，前者会让 Key 被误判为无权使用该分组。
 */
interface MemberGroupPort {
    suspend fun memberGroupId(userId: Long): Long?
}

/** 这次调用的分组从哪来。进日志：错账排查第一个要问的就是来源。 */
enum class GroupSource {
    /** 运营在后台给这把 Key 指定的分组（特价 / 专线），用户改不了。 */
    TOKEN_OVERRIDE,
    /** 用户建 / 改 Key 时自己选的分组。 */
    TOKEN_SELECTED,
    /** Key 没绑分组，走 default。 */
    DEFAULT,
}

/**
 * 拒绝原因。三者的处置方与响应码都不同，不能一律 400：
 *  - [CONFIG_BROKEN] 运营事故（兜底组缺失、令牌/用户级例外指向已删的组）→ 500，且调用方**必须 ERROR 告警**。
 *  - [NOT_ALLOWED] Key 绑定的分组已不再对这个用户开放（运营收回了专属分组 / 关了「用户可选」）→ 403，
 *    用户在控制台给 Key 换个分组即可，重试无意义。
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
 * 计费分组只由 **API Key 的配置**决定（与 platform 开放 API「价格跟着凭据的授权配置走」同一模式，
 * 见 docs/key-billing-group-design.md）：
 *
 *  - Key 绑定了分组（`gateway_tokens.group_override`）就用它；没绑就是 `default`。
 *  - 运营指定的（`group_assigned = 1`）直接生效：这是对这把 Key 的特价 / 专线，用户改不了。
 *  - 用户自己选的，每次调用都复核**此刻**仍对这个用户开放（[options]）：运营收回专属分组、
 *    关掉「用户可选」后立即 403，而不是继续按当初选到的价格计费。
 *
 * 用户级例外（gateway_group_overrides）与会员组映射不再直接决定计价，只决定「这个用户还能选哪些分组」。
 * 每一处引用都必须对应一行**活着**的 gateway_groups：悬空即拒绝（500），不回落到 default。
 */
object BillingGroupResolver {
    /** 兜底组的 code。它必须真实存在；不存在就是配置损坏，而不是「那就按 1.0 收」。 */
    const val DEFAULT_CODE = "default"

    /** 会员分组集成的开关。开着却没绑定 [MemberGroupPort] → 启动失败（见 GatewayRuntimeBootstrap）。 */
    const val ENV_MEMBER_GROUP_BILLING = "NETONAPI_MEMBER_GROUP_BILLING"

    /** 装配期可变、服务期只读（与 RelayEngine.guardedClients 同一套约定）。 */
    var memberPort: MemberGroupPort? = null

    suspend fun resolve(token: GatewayToken): GroupResolution {
        val code = token.groupOverride?.trim()?.takeIf { it.isNotEmpty() }
        if (code == null) {
            return byCode(DEFAULT_CODE, GroupSource.DEFAULT) ?: GroupResolution.Rejected(
                GroupReject.CONFIG_BROKEN,
                "no live billing group with code '$DEFAULT_CODE'; every key without a group needs it",
            )
        }
        val source = if (token.groupAssigned == 1) GroupSource.TOKEN_OVERRIDE else GroupSource.TOKEN_SELECTED
        val resolved = byCode(code, source) ?: return GroupResolution.Rejected(
            GroupReject.CONFIG_BROKEN,
            "key ${token.id} is bound to group '$code', which has no live billing group",
        )
        if (source == GroupSource.TOKEN_OVERRIDE) return resolved
        // default 组永远可用：没绑分组的 Key 本来就走它，选了它不该反而被拒
        if (code == DEFAULT_CODE || resolved.group.selectable == 1) return resolved
        return when (val allowed = grantedCodes(token.userId)) {
            is Granted.Failed -> GroupResolution.Rejected(GroupReject.RESOLUTION_FAILED, allowed.message)
            is Granted.Codes -> if (code in allowed.codes) resolved else GroupResolution.Rejected(
                GroupReject.NOT_ALLOWED,
                "group '$code' of key ${token.id} is no longer available to user=${token.userId}; choose another group for the key",
            )
        }
    }

    /**
     * 这个用户建 / 改 Key 时可选的分组：default + 运营设为「用户可选」的 + 运营单独给他开的（用户例外）
     * + 他的会员组映射的（开了会员组计费时）。会员系统解析失败时抛出，调用方按 5xx 处理。
     */
    suspend fun options(userId: Long): List<GatewayGroup> {
        val granted = when (val g = grantedCodes(userId)) {
            is Granted.Failed -> error(g.message)
            is Granted.Codes -> g.codes
        }
        return GatewayGroupTable.findAll()
            .filter { it.code == DEFAULT_CODE || it.selectable == 1 || it.code in granted }
            .sortedWith(compareBy<GatewayGroup> { it.code != DEFAULT_CODE }.thenBy { it.code })
    }

    private sealed interface Granted {
        data class Codes(val codes: Set<String>) : Granted
        data class Failed(val message: String) : Granted
    }

    /** 单独开给这个用户的分组：用户例外 + 会员组映射。 */
    private suspend fun grantedCodes(userId: Long): Granted {
        val codes = mutableSetOf<String>()
        GroupOverrideTable.oneWhere { GroupOverride::userId eq userId }?.let { codes += it.groupCode }
        val port = memberPort
        if (port != null) {
            val memberGroupId = try {
                port.memberGroupId(userId)
            } catch (e: CancellationException) {
                throw e // 协程取消必须原样上抛，吞掉会让取消语义失效
            } catch (e: Throwable) {
                return Granted.Failed("member group lookup failed for user=$userId: ${e.message}")
            }
            if (memberGroupId != null) {
                GatewayGroupTable.oneWhere { GatewayGroup::memberGroupId eq memberGroupId }?.let { codes += it.code }
            }
        }
        return Granted.Codes(codes)
    }

    private suspend fun byCode(code: String, source: GroupSource): GroupResolution.Resolved? =
        GatewayGroupTable.oneWhere { GatewayGroup::code eq code }
            ?.let { GroupResolution.Resolved(it, source) }
}
