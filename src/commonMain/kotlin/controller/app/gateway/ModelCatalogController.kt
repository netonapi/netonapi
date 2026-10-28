package controller.app.gateway

import kotlinx.serialization.Serializable
import logic.ChannelLogic
import model.ModelPrice
import neton.core.annotations.*
import neton.database.dsl.*
import table.ModelPriceTable

@Serializable
data class PublicModelVO(
    val model: String,
    /** USD / 1M tokens */
    val inputPrice: String,
    val outputPrice: String,
    val cacheReadPrice: String,
    /** 按次计价（μUSD）；非空时 token 价格不生效 */
    val perRequestPrice: Long? = null,
    /** 请求未传 max_tokens 时按此值预留；为空则该模型要求请求自带 max_tokens */
    val defaultMaxOutputTokens: Int? = null,
    /** 可调用该模型的端点能力（chat / embeddings）：只给模型名，用户会把向量模型当 chat 调 */
    val capabilities: List<String> = emptyList(),
    /** 对外展示名（如「Opus 5.5」）；空 = 显示模型 ID。 */
    val displayName: String? = null,
    /** 按上下文长度分档的售价（第一档 = 上面的价格）；单档为空。长输入按更贵的档收费，必须让用户看得到。 */
    val tiers: List<logic.PriceTier>? = null,
    /** 官方价：客户比价的锚点（spec §4.3） */
    val official: logic.PriceItemsVO? = null,
)

/**
 * 模型广场与价格。
 *
 * [@AllowAnonymous]：未登录也能浏览，这是拉新的主要入口。只暴露模型名与价格，
 * 不含渠道、Key、成本折扣等运营内部信息。
 */
@Controller("/gateway/model")
@AllowAnonymous
class ModelCatalogController(private val channelLogic: ChannelLogic) {

    @Get("/list")
    suspend fun list(): List<PublicModelVO> {
        // 只列「已上架」且「有启用渠道承载」的模型：对外提供哪些模型由模型目录决定，上游支持什么不算数。
        // 能力取承载渠道的并集：能力路由生效后，模型能被哪个端点调也是用户要知道的事实。
        val capsByModel = mutableMapOf<String, MutableSet<String>>()
        channelLogic.listEnabled().forEach { ch ->
            val caps = ch.capabilities.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            ch.models.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { m ->
                capsByModel.getOrPut(m) { mutableSetOf() }.addAll(caps)
            }
        }
        val servable = capsByModel.keys
        if (servable.isEmpty()) return emptyList()
        val markup = logic.PricingPolicy.current().markup
        return ModelPriceTable.query { orderBy(ModelPrice::model.asc()) }.list()
            // 限定开放（RULES_ONLY）的模型只对有规则的用户可见，不进公开列表
            .filter { it.listed == 1 && it.model in servable && it.access != logic.PriceBook.ACCESS_RULES_ONLY }
            .map {
                // 展示售价轨（saleOverride 或官方价 × 全局加价率）——用户看到的就是会被扣的价，
                // 官方成本价属运营内部信息，不外露。坏价模型降级展示官方价，不整个列表 500。
                val sale = runCatching { logic.SalePricing.resolve(it, markup) }.getOrDefault(it)
                PublicModelVO(
                    model = it.model,
                    inputPrice = sale.inputPrice,
                    outputPrice = sale.outputPrice,
                    cacheReadPrice = sale.cacheReadPrice,
                    perRequestPrice = sale.perRequestPrice,
                    defaultMaxOutputTokens = it.defaultMaxOutputTokens,
                    capabilities = capsByModel[it.model]?.sorted() ?: emptyList(),
                    displayName = it.displayName,
                    tiers = logic.PricingTiers.parse(sale.tiers),
                    official = logic.PriceItemsVO.of(it),
                )
            }
    }
}
