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
        val rows = prices.sortedBy { it.model }.map { row(it, byModel, groups, channels, policy) }
        return PricingCheckResult(
            markup = policy.markup,
            minMargin = policy.minMargin,
            loss = rows.count { it.status == LOSS || it.status == INVALID },
            thin = rows.count { it.status == THIN },
            ok = rows.count { it.status == OK },
            unserved = rows.count { it.status == UNSERVED },
            rows = rows,
        )
    }

    private fun row(
        price: ModelPrice,
        byModel: Map<String, ModelPrice>,
        groups: Map<String, GatewayGroup>,
        channels: List<Channel>,
        policy: PricingPolicy.Policy,
    ): PricingCheckRow {
        val sale = runCatching { SalePricing.resolve(price, policy.markup) }.getOrNull()
            ?: return PricingCheckRow(
                id = price.id, model = price.model, mode = "INVALID",
                officialInput = price.inputPrice, officialOutput = price.outputPrice,
                saleInput = "", saleOutput = "", status = INVALID,
            )
        val combos = mutableListOf<PricingCombo>()
        for (ch in channels) {
            val upstream = upstreamModel(ch, price.model) ?: continue
            val costRow = byModel[upstream] ?: price
            for (code in ch.groups.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val g = groups[code] ?: continue
                combos += tiered(sale, costRow, g, ch, policy.minMargin)
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
        )
    }

    /**
     * 分档模型逐档核算：用售价每一档的门槛作为代表输入长度，同一长度下分别选出售价档与成本档，
     * 取最差的一档。成本的分档结构可能与售价不同（渠道成本），所以按「长度」对齐而不是按序号。
     */
    private fun tiered(sale: ModelPrice, cost: ModelPrice, g: GatewayGroup, ch: Channel, minMargin: String): PricingCombo {
        val points = (PricingTiers.parse(sale.tiers).orEmpty() + PricingTiers.parse(cost.tiers).orEmpty())
            .map { it.maxPromptTokens ?: Long.MAX_VALUE }.distinct().sorted().ifEmpty { listOf(0L) }
        return points.map { len ->
            combo(PricingTiers.select(sale, len), PricingTiers.select(cost, len), g, ch, minMargin)
                .copy(tier = PricingTiers.tierIndex(sale, len))
        }.minWith(compareBy<PricingCombo> { it.marginBp }.thenBy { it.revenue - it.cost })
    }

    private fun combo(sale: ModelPrice, cost: ModelPrice, g: GatewayGroup, ch: Channel, minMargin: String): PricingCombo {
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
            val c = SalePricing.scaleMicro(officialMicro, ch.costDiscount)
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
            PricingCombo(g.code, g.ratio, ch.id, ch.name, ch.costDiscount, cost.model, dim, revenue, c, bp, status)
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
        const val OK = "OK"
        const val THIN = "THIN"
        const val LOSS = "LOSS"
        const val UNSERVED = "UNSERVED"
        const val INVALID = "INVALID"
        const val MAX_FLAGGED = 20
    }
}
