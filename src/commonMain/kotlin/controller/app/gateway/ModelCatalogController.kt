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
        // 只列「有启用渠道承载」且「已配定价」的模型——未定价模型会被网关拒绝，列出来是误导
        val servable = channelLogic.listEnabled()
            .flatMap { ch -> ch.models.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
            .toSet()
        if (servable.isEmpty()) return emptyList()
        return ModelPriceTable.query { orderBy(ModelPrice::model.asc()) }.list()
            .filter { it.model in servable }
            .map {
                // 展示售价轨（saleOverride 或官方价 × 全局加价率）——用户看到的就是会被扣的价，
                // 官方成本价属运营内部信息，不外露。坏价模型降级展示官方价，不整个列表 500。
                val sale = runCatching { logic.SalePricing.resolve(it) }.getOrDefault(it)
                PublicModelVO(
                    model = it.model,
                    inputPrice = sale.inputPrice,
                    outputPrice = sale.outputPrice,
                    cacheReadPrice = sale.cacheReadPrice,
                    perRequestPrice = sale.perRequestPrice,
                    defaultMaxOutputTokens = it.defaultMaxOutputTokens,
                )
            }
    }
}
