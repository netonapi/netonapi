package logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import model.Channel
import model.GatewayGroup
import model.ModelPrice
import table.GatewayGroupTable
import table.ModelPriceTable

/** 一个「模型 × 分组 × 渠道」组合的毛利。金额是 μUSD / 1M tokens（按次计价时是 μUSD / 次）。 */
@Serializable
data class PricingCombo(
    val groupCode: String,
    val groupRatio: String,
    val channelId: Long,
    val channelName: String,
    val costDiscount: String,
    /** 成本按哪个模型的官方价算（渠道做了模型映射时与请求模型不同）。 */
    val costModel: String,
    /** 最差的那一项：input / output / cacheRead / cacheWrite / perRequest。 */
    val dimension: String,
    val revenue: Long,
    val cost: Long,
    /** (实收 − 成本) / 实收，万分位；实收为 0 而成本 > 0 记 −10000。 */
    val marginBp: Int,
    val status: String,
    /** 最差的是第几档（0 起，单档恒为 0）。 */
    val tier: Int = 0,
    /** 成本来自哪里：channel = 渠道成本表；official = 官方价 × 渠道折扣。 */
    val costSource: String = ChannelCostLogic.SOURCE_OFFICIAL,
)

/** 承载某个对外模型的一条渠道：路由信息 + 这条渠道上这个模型的实际成本（基础档，已乘折扣）。 */
@Serializable
data class ChannelRouteVO(
    val channelId: Long,
    val name: String,
    val priority: Int,
    val weight: Int,
    val status: Int,
    val cooldownUntil: Long,
    val lastProbeOk: Int?,
    val costInput: String,
    val costOutput: String,
    /** 这个模型的成本实际来自哪里：channel（渠道成本表）/ official（官方价 × 折扣）。 */
    val costSource: String,
    /** 渠道配置的成本来源：official / upstream / manual。 */
    val configuredSource: String,
    val discount: String,
    val stale: Boolean,
    /** 渠道成本来源是上游价目接口，但最近一次同步失败、或超过 [SYNC_STALE_MILLIS] 没成功过：成本数据可能已过期。 */
    val syncFailing: Boolean = false,
)

@Serializable
data class PricingCheckRow(
    val id: Long,
    val model: String,
    /** FOLLOW 跟随全站加价率 / MODEL_MARKUP 本模型加价率 / CUSTOM 自定义售价。 */
    val mode: String,
    val modelMarkup: String? = null,
    val officialInput: String,
    val officialOutput: String,
    val saleInput: String,
    val saleOutput: String,
    val perRequestPrice: Long? = null,
    val salePerRequest: Long? = null,
    /** OK / THIN（低于最低毛利）/ LOSS（亏本）/ UNSERVED（没有启用渠道承载）/ INVALID（定价数据非法）。 */
    val status: String,
    /** 最差的组合；没有渠道承载时为空。 */
    val worst: PricingCombo? = null,
    /** 亏本或偏薄的组合，最多 [PricingCheckLogic.MAX_FLAGGED] 条。 */
    val flagged: List<PricingCombo> = emptyList(),
    val comboCount: Int = 0,
    val displayName: String? = null,
    /** 售价分档（第一档 = saleInput/saleOutput）；单档为空。 */
    val saleTiers: List<PriceTier>? = null,
    /** 承载这个模型的渠道（按优先级从高到低）。 */
    val channels: List<ChannelRouteVO> = emptyList(),
    /** 体检提示：PRIORITY_COST_INVERSION / UPSTREAM_NOT_PRICED / COST_STALE / OFFICIAL_ONLY。 */
    val hints: List<String> = emptyList(),
    /** 1 = 上架（对外提供）；0 = 只在价格库里。 */
    val listed: Int = 1,
    /** 开放范围：ALL / RULES_ONLY（spec §4.3.2） */
    val access: String = "ALL",
)

@Serializable
data class PricingCheckResult(
    val markup: String,
    val minMargin: String,
    val loss: Int,
    val thin: Int,
    val ok: Int,
    val unserved: Int,
    val rows: List<PricingCheckRow>,
)

/**
 * 定价体检：把「官方价、加价率、单模型售价、分组倍率、渠道折扣、模型映射」放在一起算一遍，
 * 回答运营最关心的那个问题 —— **有没有哪个用户在哪条渠道上，我们是在亏本卖**。
 *
 * 保存价格时的毛利闸门只看「售价 vs 官方价」，挡不住两种亏法：渠道折扣 > 1（转售加价的上游）
 * 和分组倍率 < 1（给 VIP 打折）。这里按实际路由会出现的每个组合逐一核算：
 * 渠道只服务它 `groups` 里列出的分组，所以只有交集里的组合才会真的发生。
 */
@neton.core.annotations.Logic(logger = "logic.gateway-pricing-check")
class PricingCheckLogic(private val channelLogic: ChannelLogic) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(): PricingCheckResult {
        val policy = PricingPolicy.current()
        val prices = ModelPriceTable.findAll()
        val byModel = prices.associateBy { it.model }
        val groups = GatewayGroupTable.findAll().associateBy { it.code }
        val channels = channelLogic.listEnabled()
        val costs = table.ChannelModelCostTable.findAll().associateBy { it.channelId to it.model }
        @OptIn(kotlin.time.ExperimentalTime::class)
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val rows = prices.sortedBy { it.model }.map { row(it, byModel, groups, channels, policy, costs, now) }
        // 统计只看上架的模型：价格库里的行不对外，亏不亏本无所谓
        val offered = rows.filter { it.listed == 1 }
        return PricingCheckResult(
            markup = policy.markup,
            minMargin = policy.minMargin,
            loss = offered.count { it.status == LOSS || it.status == INVALID },
            thin = offered.count { it.status == THIN },
            ok = offered.count { it.status == OK },
            unserved = offered.count { it.status == UNSERVED },
            rows = rows,
        )
    }

    private suspend fun row(
        price: ModelPrice,
        byModel: Map<String, ModelPrice>,
        groups: Map<String, GatewayGroup>,
        channels: List<Channel>,
        policy: PricingPolicy.Policy,
        costs: Map<Pair<Long, String>, model.ChannelModelCost>,
        now: Long,
    ): PricingCheckRow {
        val sale = runCatching { SalePricing.resolve(price, policy.markup) }.getOrNull()
            ?: return PricingCheckRow(
                id = price.id, model = price.model, mode = "INVALID",
                officialInput = price.inputPrice, officialOutput = price.outputPrice,
                saleInput = "", saleOutput = "", status = INVALID, listed = price.listed, access = price.access,
            )
        val combos = mutableListOf<PricingCombo>()
        val routes = mutableListOf<ChannelRouteVO>()
        for (ch in channels) {
            val upstream = upstreamModel(ch, price.model) ?: continue
            val basis = ChannelCostLogic.costForLoaded(ch, price.model, upstream, price, costs, byModel) ?: continue
            routes += ChannelRouteVO(
                channelId = ch.id, name = ch.name, priority = ch.priority, weight = ch.weight, status = ch.status,
                cooldownUntil = ch.cooldownUntil, lastProbeOk = ch.lastProbeOk,
                costInput = PricingCalc.microToDecimal(SalePricing.scaleMicro(PricingCalc.parseMicro(basis.price.inputPrice), basis.discount)),
                costOutput = PricingCalc.microToDecimal(SalePricing.scaleMicro(PricingCalc.parseMicro(basis.price.outputPrice), basis.discount)),
                costSource = basis.source, configuredSource = ch.costSource, discount = basis.discount,
                stale = costs[ch.id to price.model]?.stale == 1,
                syncFailing = ch.costSource == "upstream" &&
                    (ch.costSyncError != null || (ch.costSyncedAt ?: 0L) < now - SYNC_STALE_MILLIS),
            )
            for (code in ch.groups.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val g = groups[code] ?: continue
                // 该分组对这个模型有分组规则时按规则价核算（spec §4.3），否则平台价 × 分组倍率
                val gq = PriceBook.groupQuote(g, price)
                combos += tiered(gq.sale, basis.price, g.copy(ratio = gq.ratio), ch, basis.discount, policy.minMargin).copy(costSource = basis.source)
            }
        }
        val worst = combos.minWithOrNull(compareBy<PricingCombo> { it.marginBp }.thenBy { it.revenue - it.cost })
        val mode = SalePricing.mode(price)
        return PricingCheckRow(
            id = price.id,
            model = price.model,
            mode = mode.name,
            modelMarkup = if (mode == SalePricing.Mode.MODEL_MARKUP) modelMarkupOf(price) else null,
            officialInput = price.inputPrice,
            officialOutput = price.outputPrice,
            saleInput = sale.inputPrice,
            saleOutput = sale.outputPrice,
            perRequestPrice = price.perRequestPrice,
            salePerRequest = sale.perRequestPrice,
            status = worst?.status ?: UNSERVED,
            worst = worst,
            flagged = combos.filter { it.status != OK }.sortedBy { it.marginBp }.take(MAX_FLAGGED),
            comboCount = combos.size,
            displayName = price.displayName,
            saleTiers = PricingTiers.parse(sale.tiers),
            channels = routes.sortedByDescending { it.priority },
            hints = hintsFor(routes, now),
            listed = price.listed,
            access = price.access,
        )
    }

    /**
     * 分档模型逐档核算：用售价每一档的门槛作为代表输入长度，同一长度下分别选出售价档与成本档，
     * 取最差的一档。成本的分档结构可能与售价不同（渠道成本），所以按「长度」对齐而不是按序号。
     */
    /**
     * 一个报价在若干计费分组里实际会走的每条渠道上的毛利（定价规则保存校验、管理台价目表用，spec §4.3.5）。
     * 渠道：启用、列了该组、承载这个模型（服务清单或映射键）。按最差排序。
     */
    suspend fun combosFor(quote: PriceBook.Quote, groupCodes: Set<String>): List<PricingCombo> {
        val policy = PricingPolicy.current()
        val prices = ModelPriceTable.findAll()
        val byModel = prices.associateBy { it.model }
        val groups = GatewayGroupTable.findAll().associateBy { it.code }
        val costs = table.ChannelModelCostTable.findAll().associateBy { it.channelId to it.model }
        val out = mutableListOf<PricingCombo>()
        for (ch in channelLogic.listEnabled()) {
            val upstream = upstreamModel(ch, quote.model) ?: continue
            val basis = ChannelCostLogic.costForLoaded(ch, quote.model, upstream, quote.official, costs, byModel) ?: continue
            for (code in ch.groups.split(',').map { it.trim() }.filter { it in groupCodes }) {
                val g = groups[code] ?: continue
                out += tiered(quote.sale, basis.price, g.copy(ratio = quote.ratio), ch, basis.discount, policy.minMargin).copy(costSource = basis.source)
            }
        }
        return out.sortedWith(compareBy<PricingCombo> { it.marginBp }.thenBy { it.revenue - it.cost })
    }

    private fun tiered(sale: ModelPrice, cost: ModelPrice, g: GatewayGroup, ch: Channel, discount: String, minMargin: String): PricingCombo {
        val points = (PricingTiers.parse(sale.tiers).orEmpty() + PricingTiers.parse(cost.tiers).orEmpty())
            .map { it.maxPromptTokens ?: Long.MAX_VALUE }.distinct().sorted().ifEmpty { listOf(0L) }
        return points.map { len ->
            combo(PricingTiers.select(sale, len), PricingTiers.select(cost, len), g, ch, discount, minMargin)
                .copy(tier = PricingTiers.tierIndex(sale, len))
        }.minWith(compareBy<PricingCombo> { it.marginBp }.thenBy { it.revenue - it.cost })
    }

    private fun combo(sale: ModelPrice, cost: ModelPrice, g: GatewayGroup, ch: Channel, discount: String, minMargin: String): PricingCombo {
        // 按次计价：售价轨按次就只比按次；否则比 token 各维度（只比成本 > 0 的维度，免费维度不算亏）
        val dims: List<Triple<String, Long, Long>> = if (sale.perRequestPrice != null) {
            listOf(Triple("perRequest", sale.perRequestPrice, cost.perRequestPrice ?: 0L))
        } else listOf(
            Triple("input", PricingCalc.parseMicro(sale.inputPrice), PricingCalc.parseMicro(cost.inputPrice)),
            Triple("output", PricingCalc.parseMicro(sale.outputPrice), PricingCalc.parseMicro(cost.outputPrice)),
            Triple("cacheRead", PricingCalc.parseMicro(sale.cacheReadPrice), PricingCalc.parseMicro(cost.cacheReadPrice)),
            Triple("cacheWrite", PricingCalc.parseMicro(sale.cacheWritePrice), PricingCalc.parseMicro(cost.cacheWritePrice)),
        )
        val evaluated = dims.map { (dim, saleMicro, officialMicro) ->
            val revenue = SalePricing.scaleMicro(saleMicro, g.ratio)
            val c = SalePricing.scaleMicro(officialMicro, discount)
            val floor = SalePricing.scaleMicro(c, minMargin)
            val bp = when {
                revenue > 0 -> ((revenue - c) * 10_000 / revenue).toInt()
                c > 0 -> -10_000
                else -> 10_000
            }
            val status = when {
                revenue < c -> LOSS
                revenue < floor -> THIN
                else -> OK
            }
            PricingCombo(g.code, g.ratio, ch.id, ch.name, discount, cost.model, dim, revenue, c, bp, status)
        }
        // 只看真有成本的维度；全免费的模型取第一项展示
        return evaluated.filter { it.cost > 0 }.minByOrNull { it.marginBp } ?: evaluated.first()
    }

    /** 渠道是否承载这个模型；承载时返回实际打给上游的模型名。与 RelayRouter / RequestRewriter 的口径一致。 */
    private fun upstreamModel(ch: Channel, model: String): String? {
        val mapping = ch.modelMapping?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val mapped = mapping?.get(model)?.jsonPrimitive?.contentOrNull
        if (mapped != null) return mapped
        return if (ch.models.split(',').map { it.trim() }.contains(model)) model else null
    }

    private fun modelMarkupOf(price: ModelPrice): String? =
        price.saleOverride?.let { runCatching { json.parseToJsonElement(it).jsonObject["markup"]?.jsonPrimitive?.contentOrNull }.getOrNull() }

    companion object {
        const val HINT_PRIORITY_COST_INVERSION = "PRIORITY_COST_INVERSION"
        const val HINT_UPSTREAM_NOT_PRICED = "UPSTREAM_NOT_PRICED"
        const val HINT_COST_STALE = "COST_STALE"
        const val HINT_OFFICIAL_ONLY = "OFFICIAL_ONLY"

        /** 上游成本超过这么久没同步成功就算过期（同步每 6 小时一次，留两个周期的余量）。 */
        const val SYNC_STALE_MILLIS = 13L * 3_600_000

        /**
         * 体检提示（不改路由，只提醒）：
         *  - 优先级更高的渠道比某个健康的、更低优先级渠道贵（按基础档输入 + 输出价比较）；
         *  - 成本来源设为上游价目接口、但这个模型上游没报价（回到官方价 × 折扣估算）；
         *  - 有成本行已标「上游已不报价」；
         *  - 承载它的全是按官方原价计成本的渠道（没有便宜的中转）。
         * 健康 = 启用、不在冷却、最近测速没失败。
         */
        fun hintsFor(routes: List<ChannelRouteVO>, now: Long): List<String> {
            if (routes.isEmpty()) return emptyList()
            val hints = mutableListOf<String>()
            fun cost(r: ChannelRouteVO) = PricingCalc.parseMicro(r.costInput) + PricingCalc.parseMicro(r.costOutput)
            fun healthy(r: ChannelRouteVO) = r.status == 1 && r.cooldownUntil <= now && r.lastProbeOk != 0
            val inverted = routes.filter { healthy(it) }.let { hs ->
                hs.any { hi -> hs.any { lo -> hi.priority > lo.priority && cost(hi) > cost(lo) } }
            }
            if (inverted) hints += HINT_PRIORITY_COST_INVERSION
            if (routes.any { it.configuredSource == "upstream" && it.costSource == ChannelCostLogic.SOURCE_OFFICIAL }) hints += HINT_UPSTREAM_NOT_PRICED
            if (routes.any { it.stale || it.syncFailing }) hints += HINT_COST_STALE
            if (routes.all { it.costSource == ChannelCostLogic.SOURCE_OFFICIAL && PricingCalc.parseRatio(it.discount) == 10_000 }) hints += HINT_OFFICIAL_ONLY
            return hints
        }

        const val OK = "OK"
        const val THIN = "THIN"
        const val LOSS = "LOSS"
        const val UNSERVED = "UNSERVED"
        const val INVALID = "INVALID"
        const val MAX_FLAGGED = 20
    }
}
