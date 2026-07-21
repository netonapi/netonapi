package controller.admin.price

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
    val saleOverride: String? = null,
)

@Controller("/gateway/price")
class PriceController {

    @Get("/list")
    @Permission("gateway:price:list")
    suspend fun list(): List<ModelPrice> = ModelPriceTable.findAll()

    @Get("/get/{id}")
    @Permission("gateway:price:get")
    suspend fun get(@PathVariable id: Long): ModelPrice? = ModelPriceTable.get(id)

    @Get("/get-by-model")
    @Permission("gateway:price:get")
    suspend fun getByModel(@Query model: String): ModelPrice? =
        ModelPriceTable.oneWhere { ModelPrice::model eq model }

    @Post("/create")
    @Permission("gateway:price:create")
    suspend fun create(@Body request: SavePriceRequest): Long =
        ModelPriceTable.insert(
            ModelPrice(
                model = request.model,
                inputPrice = logic.PricingCalc.requirePrice(request.inputPrice, "inputPrice"),
                outputPrice = logic.PricingCalc.requirePrice(request.outputPrice, "outputPrice"),
                cacheReadPrice = logic.PricingCalc.requirePrice(request.cacheReadPrice, "cacheReadPrice"),
                cacheWritePrice = logic.PricingCalc.requirePrice(request.cacheWritePrice, "cacheWritePrice"),
                perRequestPrice = request.perRequestPrice, saleOverride = request.saleOverride,
            )
        ).id

    @Put("/update")
    @Permission("gateway:price:update")
    suspend fun update(@Body request: SavePriceRequest) {
        val current = ModelPriceTable.get(request.id)
            ?: throw IllegalArgumentException("gateway price id=${request.id} not found")
        ModelPriceTable.update(current.copy(
            model = request.model,
            inputPrice = logic.PricingCalc.requirePrice(request.inputPrice, "inputPrice"),
            outputPrice = logic.PricingCalc.requirePrice(request.outputPrice, "outputPrice"),
            cacheReadPrice = logic.PricingCalc.requirePrice(request.cacheReadPrice, "cacheReadPrice"),
            cacheWritePrice = logic.PricingCalc.requirePrice(request.cacheWritePrice, "cacheWritePrice"),
            perRequestPrice = request.perRequestPrice, saleOverride = request.saleOverride,
        ))
    }

    @Delete("/delete/{id}")
    @Permission("gateway:price:delete")
    suspend fun delete(@PathVariable id: Long) { ModelPriceTable.destroy(id) }
}
