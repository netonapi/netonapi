package logic

import model.Channel
import model.ChannelModelCost
import model.ModelPrice
import neton.database.dsl.*
import table.ChannelModelCostTable
import table.ModelPriceTable

/**
 * 某条渠道承载某个模型时，我们实际付出的成本价。
 * [discount] 为再乘的折扣：来自渠道成本表的价格已经是实付价，折扣恒为 "1"。
 * [source]：channel = 渠道成本表（上游价目接口或手填）；official = 官方价 × 渠道折扣。
 */
data class CostBasis(val price: ModelPrice, val discount: String, val source: String)

/**
 * 渠道成本解析（design §4.2）：
 *  1. 渠道成本表有 (渠道, 请求模型) 这一行 → 用它，不乘折扣；
 *  2. 否则 → 实际上游模型（映射目标；映射目标没有价格行则用请求模型）的官方价 × 渠道折扣。
 */
object ChannelCostLogic {

    val COST_SOURCES = setOf("official", "upstream", "manual")
    val PRICING_FORMATS = setOf("newapi", "openrouter")

    /** 管理端提交的成本设置（新建 / 修改渠道）。null = 没带这一项。 */
    data class CostInput(
        val source: String?, val discount: String?, val format: String?, val url: String?,
        val groupRatio: String?, val creditRate: String?,
    )

    /**
     * 上游的成本设置**必须由管理员明确给出**，不替他补默认值：上游几乎不可能按官方价 1:1 计费，静默补一个 1
     * 就是把成本记成官方价、毛利体检与亏本拦截全部失真。
     *
     * - 新建：成本来源、成本折扣必填；成本来源是「上游价目接口」时，价目格式、上游分组倍率、充值汇率也必填。
     * - 修改：没带的项保持原值；带了空值视同没填，按必填拒绝；改成「上游价目接口」时分组倍率与汇率必须给出
     *   （原来不是上游价目接口时，库里的 1 只是占位，不能当成管理员填过的值）。
     *
     * 校验顺序固定：来源 → 折扣 → 格式 → 分组倍率 → 汇率（错误码一次只报一个，前端据此定位字段）。
     */
    fun resolveCostConfig(input: CostInput, base: Channel, current: Channel?): Channel {
        fun blank(v: String?) = v != null && v.isBlank()
        val source = (input.source?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: current?.costSource)
            ?: rejectField("costSource", GatewayErrorCodes.CHANNEL_COST_SOURCE_REQUIRED)
        if (source !in COST_SOURCES) rejectField("costSource", GatewayErrorCodes.CHANNEL_COST_SOURCE_INVALID)
        if (blank(input.discount)) rejectField("costDiscount", GatewayErrorCodes.CHANNEL_COST_DISCOUNT_REQUIRED)
        val discount = input.discount?.trim() ?: current?.costDiscount
            ?: rejectField("costDiscount", GatewayErrorCodes.CHANNEL_COST_DISCOUNT_REQUIRED)
        val sourceGiven = input.source != null
        val format = (if (sourceGiven) input.format else current?.upstreamPricingFormat)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val url = (if (sourceGiven) input.url else current?.upstreamPricingUrl)?.trim()?.takeIf { it.isNotEmpty() }
        val wasUpstream = current?.costSource == "upstream"
        var groupRatio = input.groupRatio?.trim()?.takeIf { it.isNotEmpty() }
        var creditRate = input.creditRate?.trim()?.takeIf { it.isNotEmpty() }
        if (source == "upstream") {
            if (format !in PRICING_FORMATS) rejectField("upstreamPricingFormat", GatewayErrorCodes.CHANNEL_PRICING_FORMAT_REQUIRED)
            if (groupRatio == null && (blank(input.groupRatio) || !wasUpstream)) rejectField("upstreamGroupRatio", GatewayErrorCodes.CHANNEL_UPSTREAM_GROUP_RATIO_REQUIRED)
            if (creditRate == null && (blank(input.creditRate) || !wasUpstream)) rejectField("upstreamCreditRate", GatewayErrorCodes.CHANNEL_UPSTREAM_CREDIT_RATE_REQUIRED)
        }
        groupRatio = groupRatio ?: current?.upstreamGroupRatio ?: "1"
        creditRate = creditRate ?: current?.upstreamCreditRate ?: "1"
        return base.copy(
            costSource = source,
            costDiscount = PricingCalc.requireRatio(discount, "costDiscount"),
            upstreamPricingFormat = format?.takeIf { it in PRICING_FORMATS },
            upstreamPricingUrl = url,
            upstreamGroupRatio = PricingCalc.requireRatio(groupRatio, "upstreamGroupRatio"),
            upstreamCreditRate = PricingCalc.requireRatio(creditRate, "upstreamCreditRate"),
        )
    }

    const val SOURCE_CHANNEL = "channel"
    const val SOURCE_OFFICIAL = "official"

    suspend fun costFor(channel: Channel, requestModel: String, upstreamModel: String, requestPrice: ModelPrice?): CostBasis? {
        val row = ChannelModelCostTable.oneWhere {
            and(ChannelModelCost::channelId eq channel.id, ChannelModelCost::model eq requestModel)
        }
        val costs = row?.let { mapOf((channel.id to requestModel) to it) }.orEmpty()
        val prices = buildMap {
            requestPrice?.let { put(requestModel, it) }
            if (upstreamModel != requestModel) ModelPriceTable.oneWhere { ModelPrice::model eq upstreamModel }?.let { put(upstreamModel, it) }
        }
        return costForLoaded(channel, requestModel, upstreamModel, requestPrice, costs, prices)
    }

    /** 纯函数版本：体检等批量场景先把成本表、价格表一次读出来再逐个算。 */
    fun costForLoaded(
        channel: Channel,
        requestModel: String,
        upstreamModel: String,
        requestPrice: ModelPrice?,
        costs: Map<Pair<Long, String>, ChannelModelCost>,
        prices: Map<String, ModelPrice>,
    ): CostBasis? {
        // 上游同步来的行只在成本来源仍是「上游价目接口」时生效：切回官方价后，界面写的是「官方价 × 折扣」，
        // 旧的上游行（已冻结不再更新）不能继续悄悄决定成本。手填的行始终生效。
        costs[channel.id to requestModel]
            ?.takeIf { it.source != "upstream" || channel.costSource == "upstream" }
            ?.let { return CostBasis(it.toModelPrice(requestModel), "1", SOURCE_CHANNEL) }
        val official = prices[upstreamModel] ?: requestPrice ?: prices[requestModel] ?: return null
        return CostBasis(official, channel.costDiscount, SOURCE_OFFICIAL)
    }
}

fun ChannelModelCost.toModelPrice(model: String): ModelPrice = ModelPrice(
    model = model,
    inputPrice = inputPrice, outputPrice = outputPrice, cacheReadPrice = cacheReadPrice, cacheWritePrice = cacheWritePrice,
    perRequestPrice = perRequestPrice, tiers = tiers,
)
