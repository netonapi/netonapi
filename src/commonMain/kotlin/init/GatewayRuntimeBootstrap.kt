package init

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
    }
}
