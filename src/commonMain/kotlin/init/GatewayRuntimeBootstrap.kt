package init

import logic.BillingGroupResolver
import logic.MemberDirectory
import logic.MemberDirectoryPort
import logic.MemberGroupPort
import logic.QuotaRechargeLogic
import logic.RechargeOrderPort
import logic.RateLimiter
import neton.core.component.NetonContext
import neton.logging.LoggerFactory
import neton.redis.RedisClient

/**
 * gateway 手写 runtime bootstrap（KSP 约定：`init.{Id}RuntimeBootstrap`，在 logics 之后、routes 之前调用）。
 *
 * 存在的唯一理由是 [RateLimiter] 的 Redis 注入：KSP 的 @Logic 装配**跳过带默认值的构造参数**
 * （LogicProcessor 把默认值参数视为「自 provision」），于是 `RateLimiter(log, redis: RedisClient? = null)`
 * 生成出来只传 log —— redis 恒为 null，限流一直跑在进程内计数上，而 KDoc 却宣称「配了 Redis 走 Redis
 * （多实例共享计数）」。单实例部署看不出差别；多实例时每个实例各算各的，实际放行量被放大到实例数倍，
 * 这正是限流最典型的静默失效（不报错、不告警，只是不再限）。
 *
 * 生成的装配是 absent-才-bind，这里重绑即覆盖（member 模块的 MemberAuthLogic 同理）。
 * 路由在 bootstrap 之后初始化，且控制器每次请求都 `ctx.get(RateLimiter::class)`，拿到的就是这个实例。
 */
object GatewayRuntimeBootstrap {
    fun initialize(ctx: NetonContext) {
        // 本模块的全局设置定义登记进注册表，后台可见可改
        ctx.getOrNull(setting.SettingDefinitionRegistry::class)?.register(setting.GatewaySettingKeys.definitions)
        logic.NetGuard.checkLegacyTrustedProxies(neton.core.config.getEnv(logic.NetGuard.ENV_TRUSTED_PROXIES))
        // 渠道客户端按需创建（各自的代理与超时），建客户端的能力来自应用。
        logic.RelayEngine.clientProvider = ctx.getOrNull(neton.http.client.HttpClientProvider::class) ?: error(
            "module-gateway needs an HttpClientProvider bound in NetonContext. Bind one in the application: " +
                "bind(HttpClientProvider::class, HttpClientProvider { HttpClient.create(it) }).",
        )
        val log = ctx.get(LoggerFactory::class).get("logic.gateway-ratelimit")
        val redis = ctx.getOrNull(RedisClient::class)
        if (redis == null) {
            // 明确说出来：静默降级成「每实例各限各的」比没有降级更难发现
            log.warn("rate limit: RedisClient not bound, counters are in-process only (not shared across instances)")
        }
        ctx.bind(RateLimiter::class, RateLimiter(log = log, redis = redis))

        // Gemini thought signature 的状态引用存储（neton-docs spec/ai.md §4.5.1 机制 B）。
        // 多实例部署必须走 Redis：进程内存储在别的实例上取不到，下一轮就会丢签名。
        if (redis != null) {
            logic.ThoughtSignatureVaults.store = logic.RedisThoughtSignatureStore(redis)
        } else {
            log.warn("gemini thought signatures: RedisClient not bound, signature references are in-process only (not shared across instances)")
        }
        fun envFlag(name: String, default: Boolean): Boolean =
            neton.core.config.getEnv(name)?.trim()?.lowercase()?.let { it in setOf("1", "true", "yes", "on") } ?: default
        logic.ThoughtSignatureVaults.echoToOpenAiClients = envFlag("NEWGATE_GEMINI_SIGNATURE_ECHO", true)
        logic.ThoughtSignatureBypassPolicy.enabled = envFlag("NEWGATE_GEMINI_SIGNATURE_BYPASS", true)
        neton.core.config.getEnv("NEWGATE_GEMINI_SIGNATURE_TTL_HOURS")?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let {
            logic.ThoughtSignatureVaults.ttl = kotlin.time.Duration.parse("${it}h")
        }

        // 充值下单能力同样来自装配层（gateway 不认识 payment）。absent 就保持 null：
        // 充值接口会明确报错，而不是静默把单建到别处、或凭空发放额度。
        ctx.getOrNull(QuotaRechargeLogic::class)?.orderPort = ctx.getOrNull(RechargeOrderPort::class)

        // 会员分组 → 计费组：gateway 不认识 member，port 由装配层实现并绑定。
        // 开关打开却没绑定就**启动失败** —— 静默跳过会员映射会退回 default 组，
        // 那正是本批要消灭的「不报错的错账」，不能从装配层再引进来一次。
        val memberBilling = neton.core.config.getEnv(BillingGroupResolver.ENV_MEMBER_GROUP_BILLING)
            ?.trim()?.lowercase() in setOf("1", "true", "yes", "on")
        val port = ctx.getOrNull(MemberGroupPort::class)
        if (memberBilling && port == null) {
            error(
                "module-gateway: ${BillingGroupResolver.ENV_MEMBER_GROUP_BILLING} is on but no MemberGroupPort " +
                    "is bound in NetonContext. Bind one in the application (it reads the member's group id), " +
                    "or unset the switch.",
            )
        }
        // 开关门控：绑了 port 但开关没开就不启用会员映射，并且**说出来** ——
        // 否则「我明明绑了怎么没生效」会被当成解析器的 bug 去查。
        BillingGroupResolver.memberPort = if (memberBilling) port else null
        if (port != null && !memberBilling) {
            ctx.get(LoggerFactory::class).get("init.gateway-bootstrap").info(
                "member group billing is off: set ${BillingGroupResolver.ENV_MEMBER_GROUP_BILLING}=true " +
                    "to map member groups onto billing groups"
            )
        }

        // 会员名录：管理端写用户级计费例外时校验 userId。与上面那个 port 不同，这里**没有开关**——
        // 校验「这个人存在」不是可选特性，开着关着没有中间态可谈。absent 就保持 null，写入校验
        // 退化成只校验正数，也就是本 port 要消灭的那个缺陷原样留着，所以**必须说出来**：
        // 静默退化正是「大客户谈判价没生效而双方都不报错」的原形（取舍见 MemberDirectory 的 KDoc）。
        MemberDirectory.port = ctx.getOrNull(MemberDirectoryPort::class)
        if (MemberDirectory.port == null) {
            ctx.get(LoggerFactory::class).get("init.gateway-bootstrap").warn(
                "member directory: no MemberDirectoryPort bound, so admin writes to " +
                    "gateway_group_overrides cannot check that userId exists — a mistyped id leaves " +
                    "the customer on the default billing group with no error on either side"
            )
        }
    }
}
