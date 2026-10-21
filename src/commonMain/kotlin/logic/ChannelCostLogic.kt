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

    /** 校验并规范化渠道的成本设置；返回填好的渠道副本。 */
    fun requireCostConfig(ch: Channel): Channel {
        val source = ch.costSource.trim().lowercase()
        if (source !in COST_SOURCES) rejectField("costSource", GatewayErrorCodes.CHANNEL_COST_SOURCE_INVALID)
        val format = ch.upstreamPricingFormat?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (source == "upstream" && format !in PRICING_FORMATS) rejectField("upstreamPricingFormat", GatewayErrorCodes.CHANNEL_PRICING_FORMAT_REQUIRED)
        return ch.copy(
            costSource = source,
            upstreamPricingFormat = format?.takeIf { it in PRICING_FORMATS },
            upstreamPricingUrl = ch.upstreamPricingUrl?.trim()?.takeIf { it.isNotEmpty() },
            upstreamGroupRatio = PricingCalc.requireRatio(ch.upstreamGroupRatio, "upstreamGroupRatio"),
            upstreamCreditRate = PricingCalc.requireRatio(ch.upstreamCreditRate, "upstreamCreditRate"),
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
