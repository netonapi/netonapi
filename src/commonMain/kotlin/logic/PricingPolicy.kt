package logic

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import setting.GatewaySettingKeys

/**
 * 全站定价参数：加价率与最低毛利（后台「配置管理」可改，见 [GatewaySettingKeys.PRICING_MARKUP]）。
 *
 * 中转热路径每个请求都要用到加价率，配置读一次是一次查库；这里缓存 [TTL_MILLIS]，
 * 后台改完最多这么久生效。配置值非法（越出 (0,100]）时退回默认值并记日志 —— 设置页本身只校验了是数字。
 */
object PricingPolicy {

    data class Policy(val markup: String, val minMargin: String)

    /** 由 [init.GatewayRuntimeBootstrap] 注入；没注入（单元测试、老装配）时用定义里的默认值。 */
    var settings: SystemSettingLogic? = null

    const val TTL_MILLIS = 10_000L

    private var cached: Policy? = null
    private var cachedAt = 0L

    @OptIn(ExperimentalTime::class)
    suspend fun current(): Policy {
        val now = Clock.System.now().toEpochMilliseconds()
        cached?.let { if (now - cachedAt < TTL_MILLIS) return it }
        val s = settings
        val policy = Policy(
            markup = valid(s?.get(GatewaySettingKeys.PRICING_MARKUP), GatewaySettingKeys.PRICING_MARKUP.default),
            minMargin = valid(s?.get(GatewaySettingKeys.PRICING_MIN_MARGIN), GatewaySettingKeys.PRICING_MIN_MARGIN.default),
        )
        cached = policy
        cachedAt = now
        return policy
    }

    /** 后台刚改了配置时调用，让下一个请求立即读到新值。 */
    fun invalidate() { cached = null }

    private fun valid(raw: String?, fallback: String): String =
        raw?.let { runCatching { PricingCalc.requireRatio(it) }.getOrNull() } ?: PricingCalc.requireRatio(fallback)
}
