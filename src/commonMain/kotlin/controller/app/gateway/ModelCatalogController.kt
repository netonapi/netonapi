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
                PublicModelVO(
                    model = it.model,
                    inputPrice = it.inputPrice,
                    outputPrice = it.outputPrice,
                    cacheReadPrice = it.cacheReadPrice,
                    perRequestPrice = it.perRequestPrice,
                    defaultMaxOutputTokens = it.defaultMaxOutputTokens,
                )
            }
    }
}
