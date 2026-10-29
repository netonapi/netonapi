package logic

import model.Channel
import model.GatewayGroup
import model.ModelPrice
import model.PriceRule
import neton.database.dsl.*
import table.GatewayGroupTable
import table.ModelPriceTable
import table.PriceRuleTable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 查价（docs/gateway-pricing-spec.md §4.3）：**分组价格表全站缓存一份，叠加用户规则**。
 *
 * ```
 * 查价(用户, 分组, 模型) = 用户规则[用户][模型] ?: 分组规则[分组][模型] ?: 平台价 × 分组倍率
 * ```
 *
 * - 报价 [Quote] 用现有定价快照的形状表达（售价 × 倍率 = 实付）：平台价时是「平台价 × 分组倍率」；
 *   官方价折扣是「官方价 × 折扣」；固定价是「固定价 × 1」。命中规则时不再乘分组倍率。结算代码因此不用改。
 * - 全站缓存（价格行、分组、分组规则、各分组可承载的模型）一次读库建好，[TTL_MILLIS] 内复用；用户规则按用户缓存，
 *   没有规则的用户缓存空表。本进程的管理端写入调 [invalidate] / [invalidateUser] 立即生效；TTL 兜底多实例。
 * - 请求时只查内存，热路径不读价格表。
 */
object PriceBook {

    const val SUBJECT_GROUP = "GROUP"
    const val SUBJECT_USER = "USER"
    const val MODE_PLATFORM = "PLATFORM"
    const val MODE_OFFICIAL_DISCOUNT = "OFFICIAL_DISCOUNT"
    const val MODE_FIXED = "FIXED"
    val MODES = setOf(MODE_PLATFORM, MODE_OFFICIAL_DISCOUNT, MODE_FIXED)
    const val ACCESS_ALL = "ALL"
    const val ACCESS_RULES_ONLY = "RULES_ONLY"
    val ACCESSES = setOf(ACCESS_ALL, ACCESS_RULES_ONLY)

    const val SOURCE_USER = "USER"
    const val SOURCE_GROUP = "GROUP"
    const val SOURCE_PLATFORM = "PLATFORM"

    /** 缓存有效期；`NETONAPI_PRICEBOOK_TTL_MILLIS` 可调（0 = 每次读库，harness 用；多实例时它就是最长生效延迟）。 */
    val TTL_MILLIS: Long by lazy {
        neton.core.config.getEnv("NETONAPI_PRICEBOOK_TTL_MILLIS")?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: 5_000L
    }
    private const val MAX_CACHED_USERS = 20_000

    /** 一个模型的报价：实付 = [sale] × [ratio]；[official] 是成本基准与对客户展示的锚点。 */
    data class Quote(
        val model: String,
        val official: ModelPrice,
        val sale: ModelPrice,
        val ratio: String,
        /** USER / GROUP / PLATFORM */
        val source: String,
        val rule: PriceRule? = null,
    )

    sealed interface Lookup {
        data class Ok(val quote: Quote) : Lookup
        /** 没有价格行 */
        data object NotPriced : Lookup
        /** 未上架，或限定开放而用户 / 分组都没有规则 */
        data object NotAvailable : Lookup
        /** 定价数据非法（运营事故） */
        data class Invalid(val message: String) : Lookup
    }

    private class Site(
        val builtAt: Long,
        val markup: String,
        /** 全部价格行（含未上架），按模型名 */
        val prices: Map<String, ModelPrice>,
        val groups: Map<String, GatewayGroup>,
        /** 分组 code → 模型 → 规则 */
        val groupRules: Map<String, Map<String, PriceRule>>,
        /** 分组 code → 该分组有渠道承载的模型（渠道未被手动停用、列了该组、服务清单或映射键有该模型） */
        val routable: Map<String, Set<String>>,
    )

    private class UserEntry(val builtAt: Long, val rules: Map<String, PriceRule>)

    private var site: Site? = null
    private var users: Map<Long, UserEntry> = emptyMap()

    /** 价格行、上架、开放范围、分组、分组规则、渠道配置有变化时调用（本进程立即生效）。 */
    fun invalidate() { site = null }

    /** 某个用户的规则有变化时调用。 */
    fun invalidateUser(userId: Long) { users = users - userId }

    @OptIn(ExperimentalTime::class)
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private suspend fun site(): Site {
        val markup = PricingPolicy.current().markup
        site?.let { if (now() - it.builtAt < TTL_MILLIS && it.markup == markup) return it }
        val prices = ModelPriceTable.findAll().associateBy { it.model }
        val groups = GatewayGroupTable.findAll().associateBy { it.code }
        val groupRules = PriceRuleTable.query { where { PriceRule::subjectType eq SUBJECT_GROUP } }.list()
            .groupBy { it.subjectId }.mapValues { (_, rs) -> rs.associateBy { it.model } }
        val routable = mutableMapOf<String, MutableSet<String>>()
        table.ChannelTable.findAll().filter { it.status != 0 }.forEach { ch ->
            val served = servedModels(ch)
            ch.groups.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { g ->
                routable.getOrPut(g) { mutableSetOf() }.addAll(served)
            }
        }
        return Site(now(), markup, prices, groups, groupRules, routable).also { site = it }
    }

    private suspend fun userRules(userId: Long): Map<String, PriceRule> {
        users[userId]?.let { if (now() - it.builtAt < TTL_MILLIS) return it.rules }
        val rules = PriceRuleTable.query {
            where { and(PriceRule::subjectType eq SUBJECT_USER, PriceRule::subjectId eq userId.toString()) }
        }.list().associateBy { it.model }
        val current = if (users.size >= MAX_CACHED_USERS) emptyMap() else users
        users = current + (userId to UserEntry(now(), rules))
        return rules
    }

    /** 渠道承载的模型：服务清单 + 模型映射的键（与 [RelayRouter] 的匹配口径一致）。 */
    private fun servedModels(ch: Channel): Set<String> {
        val out = ch.models.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        ch.modelMapping?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject }
                .getOrNull()?.keys?.let { out.addAll(it) }
        }
        return out
    }

    /**
     * 转发用：这个用户经这个分组调这个模型的报价。不判断渠道是否承载（转发的候选渠道另有判断与错误码）。
     */
    suspend fun lookup(userId: Long, groupCode: String, model: String): Lookup {
        val s = site()
        val official = s.prices[model] ?: return Lookup.NotPriced
        if (official.listed != 1) return Lookup.NotAvailable
        val userRule = userRules(userId)[model]
        val groupRule = s.groupRules[groupCode]?.get(model)
        if (official.access == ACCESS_RULES_ONLY && userRule == null && groupRule == null) return Lookup.NotAvailable
        val group = s.groups[groupCode] ?: return Lookup.Invalid("billing group '$groupCode' is missing")
        return runCatching {
            Lookup.Ok(
                userRule?.let { quote(official, group, it, SOURCE_USER, s.markup) }
                    ?: groupRule?.let { quote(official, group, it, SOURCE_GROUP, s.markup) }
                    ?: platform(official, group, s.markup),
            )
        }.getOrElse { Lookup.Invalid(it.message ?: "invalid pricing") }
    }

    /**
     * 列表用：这个用户在这个分组下能用的全部模型与报价（`/v1/models`、控制台、管理台价目表）。
     * 只列上架、开放给他、且该分组有渠道承载的模型；定价非法的模型跳过（转发时会以 500 报出）。
     */
    suspend fun list(userId: Long?, groupCode: String): List<Quote> {
        val s = site()
        val routable = s.routable[groupCode].orEmpty()
        val group = s.groups[groupCode] ?: return emptyList()
        val uRules = userId?.let { userRules(it) }.orEmpty()
        val gRules = s.groupRules[groupCode].orEmpty()
        return s.prices.values.asSequence()
            .filter { it.listed == 1 && it.model in routable }
            .filter { it.access != ACCESS_RULES_ONLY || it.model in uRules || it.model in gRules }
            .mapNotNull { p ->
                runCatching {
                    uRules[p.model]?.let { quote(p, group, it, SOURCE_USER, s.markup) }
                        ?: gRules[p.model]?.let { quote(p, group, it, SOURCE_GROUP, s.markup) }
                        ?: platform(p, group, s.markup)
                }.getOrNull()
            }
            .sortedBy { it.model }
            .toList()
    }

    /** 体检用：分组规则 ?: 平台价 × 分组倍率（不含用户规则）。 */
    suspend fun groupQuote(group: GatewayGroup, official: ModelPrice): Quote {
        val s = site()
        return s.groupRules[group.code]?.get(official.model)?.let { quote(official, group, it, SOURCE_GROUP, s.markup) }
            ?: platform(official, group, s.markup)
    }

    private fun platform(official: ModelPrice, group: GatewayGroup, markup: String): Quote =
        Quote(official.model, official, SalePricing.resolve(official, markup), group.ratio, SOURCE_PLATFORM)

    /** 规则 → 报价。非法规则（理论上保存时已挡）抛异常，由调用方按定价非法处理。 */
    fun quote(official: ModelPrice, group: GatewayGroup, rule: PriceRule, source: String, markup: String): Quote = when (rule.mode) {
        MODE_PLATFORM -> Quote(official.model, official, SalePricing.resolve(official, markup), group.ratio, source, rule)
        // 官方价折扣：售价轨就是官方价（逐项、逐档），倍率 = 折扣；不再乘分组倍率
        MODE_OFFICIAL_DISCOUNT -> Quote(
            official.model, official, official.copy(saleOverride = null),
            PricingCalc.requireRatio(rule.discount ?: error("discount is required")), source, rule,
        )
        // 固定价：与 sale_override 同结构，按 1 倍计；只用于不分档的模型
        MODE_FIXED -> {
            require(official.tiers.isNullOrBlank()) { "fixed price is not allowed for tiered model ${official.model}" }
            Quote(official.model, official, SalePricing.resolve(official.copy(saleOverride = rule.prices), markup), "1", source, rule)
        }
        else -> error("unknown pricing rule mode ${rule.mode}")
    }

    /** 实付单价（展示用）：售价轨各项 × 倍率，十进制串。 */
    fun effective(q: Quote): ModelPrice {
        fun mul(v: String) = PricingCalc.microToDecimal(SalePricing.scaleMicro(PricingCalc.parseMicro(v), q.ratio))
        return q.sale.copy(
            inputPrice = mul(q.sale.inputPrice), outputPrice = mul(q.sale.outputPrice),
            cacheReadPrice = mul(q.sale.cacheReadPrice), cacheWritePrice = mul(q.sale.cacheWritePrice),
            perRequestPrice = q.sale.perRequestPrice?.let { SalePricing.scaleMicro(it, q.ratio) },
        )
    }

    /**
     * 相当于官方价的折扣（展示用，万分位）：输出价（没有输出价时用输入价；按次计价用按次价）的实付 / 官方。
     * 官方价为 0 时返回 null。
     */
    fun discountBp(q: Quote): Long? {
        val eff = effective(q)
        fun pick(p: ModelPrice): Long = when {
            p.perRequestPrice != null -> p.perRequestPrice
            PricingCalc.parseMicro(p.outputPrice) > 0 -> PricingCalc.parseMicro(p.outputPrice)
            else -> PricingCalc.parseMicro(p.inputPrice)
        }
        val off = pick(q.official)
        if (off <= 0) return null
        return pick(eff) * 10_000 / off
    }
}
