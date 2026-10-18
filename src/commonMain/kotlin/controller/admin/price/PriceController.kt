package controller.admin.price

import logic.GatewayErrorCodes
import neton.core.http.HttpException
import neton.core.http.NetonErrorCode
import kotlinx.serialization.Serializable
import model.ModelPrice
import neton.core.annotations.*
import neton.database.dsl.*
import table.ModelPriceTable

@Serializable
data class SavePriceRequest(
    val id: Long = 0,
    val model: String,
    val inputPrice: String = "0",
    val outputPrice: String = "0",
    val cacheReadPrice: String = "0",
    val cacheWritePrice: String = "0",
    val perRequestPrice: Long? = null,
    /** 按 token 计价的模型没配它、请求又不带 max_tokens 时会被拒（output_cap_required）。 */
    val defaultMaxOutputTokens: Int? = null,
    val saleOverride: String? = null,
)

@Serializable
data class PriceImportRequest(val items: List<SavePriceRequest>, val overwrite: Boolean = true)

@Controller("/gateway/price")
class PriceController(private val importLogic: logic.PriceImportLogic) {

    @Get("/list")
    @Permission("gateway:price:query")
    suspend fun list(): List<ModelPrice> = ModelPriceTable.findAll()

    @Get("/get/{id}")
    @Permission("gateway:price:query")
    suspend fun get(@PathVariable id: Long): ModelPrice? = ModelPriceTable.get(id)

    @Get("/get-by-model")
    @Permission("gateway:price:query")
    suspend fun getByModel(@Query model: String): ModelPrice? =
        ModelPriceTable.oneWhere { ModelPrice::model eq model }

    @Post("/create")
    @Permission("gateway:price:create")
    suspend fun create(@Body request: SavePriceRequest): Long {
        val price = toModelPrice(request)
        logic.SalePricing.requireMargin(price)
        return ModelPriceTable.insert(price).id
    }

    @Put("/update")
    @Permission("gateway:price:update")
    suspend fun update(@Body request: SavePriceRequest) {
        val current = ModelPriceTable.get(request.id)
            ?: throw HttpException(NetonErrorCode.RESOURCE_NOT_FOUND, GatewayErrorCodes.PRICE_NOT_FOUND)
        val price = toModelPrice(request)
        logic.SalePricing.requireMargin(price)
        ModelPriceTable.update(current.copy(
            model = price.model,
            inputPrice = price.inputPrice, outputPrice = price.outputPrice,
            cacheReadPrice = price.cacheReadPrice, cacheWritePrice = price.cacheWritePrice,
            perRequestPrice = price.perRequestPrice, defaultMaxOutputTokens = price.defaultMaxOutputTokens,
            saleOverride = price.saleOverride,
        ))
    }

    /** 官方价（成本轨）+ 售价覆盖；售价低于毛利底线由 [logic.SalePricing.requireMargin] 拒绝。 */
    /** 批量导入：见 [logic.PriceImportLogic]。 */
    @Post("/import")
    @Permission("gateway:price:create")
    suspend fun import(@Body request: PriceImportRequest): logic.PriceImportResult =
        importLogic.importItems(request.items.map { toItem(it) }, request.overwrite)

    /** 立即从 NEWGATE_PRICE_SYNC_URL 拉一次价目表（定时任务 gateway-price-sync 每天也会做）。 */
    @Post("/sync")
    @Permission("gateway:price:create")
    suspend fun sync(): logic.PriceImportResult = importLogic.syncFromEnv()
        ?: throw HttpException(NetonErrorCode.OPERATION_NOT_ALLOWED, GatewayErrorCodes.PRICE_SYNC_NOT_CONFIGURED)

    private fun toItem(r: SavePriceRequest) = logic.PriceImportItem(
        model = r.model, inputPrice = r.inputPrice, outputPrice = r.outputPrice,
        cacheReadPrice = r.cacheReadPrice, cacheWritePrice = r.cacheWritePrice,
        perRequestPrice = r.perRequestPrice, defaultMaxOutputTokens = r.defaultMaxOutputTokens, saleOverride = r.saleOverride,
    )

    private fun toModelPrice(request: SavePriceRequest): ModelPrice = ModelPrice(
        model = request.model,
        inputPrice = logic.PricingCalc.requirePrice(request.inputPrice, "inputPrice"),
        outputPrice = logic.PricingCalc.requirePrice(request.outputPrice, "outputPrice"),
        cacheReadPrice = logic.PricingCalc.requirePrice(request.cacheReadPrice, "cacheReadPrice"),
        cacheWritePrice = logic.PricingCalc.requirePrice(request.cacheWritePrice, "cacheWritePrice"),
        perRequestPrice = request.perRequestPrice, saleOverride = request.saleOverride,
        defaultMaxOutputTokens = request.defaultMaxOutputTokens?.takeIf { it > 0 },
    )

    @Delete("/delete/{id}")
    @Permission("gateway:price:delete")
    suspend fun delete(@PathVariable id: Long) { ModelPriceTable.destroy(id) }
}
