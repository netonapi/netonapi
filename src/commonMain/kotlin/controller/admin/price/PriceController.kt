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
    /** 按 token 计价的模型没配它、请求又不带 max_tokens 时会被拒（output_cap_required）。 */
    val defaultMaxOutputTokens: Int? = null,
    val saleOverride: String? = null,
)

@Serializable
data class PriceImportRequest(val items: List<SavePriceRequest>, val overwrite: Boolean = true)

@Serializable
data class PriceImportResult(val created: Int, val updated: Int, val skipped: Int, val errors: List<String> = emptyList())

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
    suspend fun create(@Body request: SavePriceRequest): Long {
        val price = toModelPrice(request)
        logic.SalePricing.requireMargin(price)
        return ModelPriceTable.insert(price).id
    }

    @Put("/update")
    @Permission("gateway:price:update")
    suspend fun update(@Body request: SavePriceRequest) {
        val current = ModelPriceTable.get(request.id)
            ?: throw IllegalArgumentException("gateway price id=${request.id} not found")
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
    /**
     * 批量导入：按 model 去重后逐条 upsert。先把全部条目校验一遍，有一条不合法就整批不写 ——
     * 运营贴进来一份 200 行的价目表，写到第 137 行报错停下，剩下的哪些写了哪些没写要对着列表数。
     * [PriceImportRequest.overwrite] = false 时已存在的模型跳过（只补新模型，不动已经调过的价）。
     */
    @Post("/import")
    @Permission("gateway:price:create")
    suspend fun import(@Body request: PriceImportRequest): PriceImportResult {
        val errors = mutableListOf<String>()
        val parsed = request.items.mapIndexedNotNull { i, item ->
            if (item.model.isBlank()) { errors += "#${i + 1}: model 为空"; return@mapIndexedNotNull null }
            runCatching { toModelPrice(item.copy(model = item.model.trim())).also { logic.SalePricing.requireMargin(it) } }
                .onFailure { errors += "#${i + 1} ${item.model}: ${it.message ?: it::class.simpleName}" }
                .getOrNull()
        }
        if (errors.isNotEmpty()) return PriceImportResult(0, 0, 0, errors)
        var created = 0; var updated = 0; var skipped = 0
        for (price in parsed.distinctBy { it.model }) {
            val current = ModelPriceTable.oneWhere { ModelPrice::model eq price.model }
            when {
                current == null -> { ModelPriceTable.insert(price.copy(source = "import")); created++ }
                !request.overwrite -> skipped++
                else -> {
                    ModelPriceTable.update(current.copy(
                        inputPrice = price.inputPrice, outputPrice = price.outputPrice,
                        cacheReadPrice = price.cacheReadPrice, cacheWritePrice = price.cacheWritePrice,
                        perRequestPrice = price.perRequestPrice ?: current.perRequestPrice,
                        defaultMaxOutputTokens = price.defaultMaxOutputTokens ?: current.defaultMaxOutputTokens,
                        saleOverride = price.saleOverride ?: current.saleOverride,
                        source = "import",
                    ))
                    updated++
                }
            }
        }
        return PriceImportResult(created, updated, skipped)
    }

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
