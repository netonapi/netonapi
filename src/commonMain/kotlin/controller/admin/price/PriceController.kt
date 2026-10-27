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
    /** 分档价格 JSON（见 PricingTiers）。update 时不带 = 保留原值，传空串 = 清除。 */
    val tiers: String? = null,
    /** 对外展示名；update 时不带 = 保留原值，传空串 = 清除。 */
    val displayName: String? = null,
    /** 上架（对外提供）：新建缺省 1；更新时不带 = 保留原状态（见 docs/model-catalog-design.md）。 */
    val listed: Int? = null,
)

@Serializable
data class PriceImportRequest(val items: List<SavePriceRequest>, val overwrite: Boolean = true)

@Controller("/gateway/price")
class PriceController(
    private val importLogic: logic.PriceImportLogic,
    private val checkLogic: logic.PricingCheckLogic,
    private val calibrationLogic: logic.PriceCalibrationLogic,
) {

    @Get("/list")
    @Permission("gateway:price:query")
    suspend fun list(): List<ModelPrice> = ModelPriceTable.findAll()

    /**
     * 定价体检：每个模型在所有实际可能发生的「分组 × 渠道」组合里，最差的毛利是多少、有没有亏本。
     * 见 [logic.PricingCheckLogic]。
     */
    @Get("/check")
    @Permission("gateway:price:query")
    suspend fun check(): logic.PricingCheckResult = checkLogic.check()

    /** 校准官方价·预览：拉价源，逐个比对我们的官方价，列出差异与渠道已承载但没定价的模型。不写库。 */
    @Get("/calibrate/preview")
    @Permission("gateway:price:query")
    suspend fun calibratePreview(): logic.CalibrationPreview = calibrationLogic.preview()

    /** 校准官方价·应用：只写勾选的模型；整批校验与毛利闸门同批量导入。 */
    @Post("/calibrate/apply")
    @Permission("gateway:price:update")
    suspend fun calibrateApply(@Body request: logic.CalibrationApplyRequest, identity: neton.core.interfaces.Identity): logic.PriceImportResult =
        calibrationLogic.apply(request.models, identity.id.toLongOrNull())

    @Get("/get/{id}")
    @Permission("gateway:price:query")
    suspend fun get(@PathVariable id: Long): ModelPrice? = ModelPriceTable.get(id)

    @Get("/get-by-model")
    @Permission("gateway:price:query")
    suspend fun getByModel(@Query model: String): ModelPrice? =
        ModelPriceTable.oneWhere { ModelPrice::model eq model }

    @Post("/create")
    @Permission("gateway:price:create")
    suspend fun create(@Body request: SavePriceRequest, identity: neton.core.interfaces.Identity): Long {
        val price = toModelPrice(request).copy(listed = if (request.listed == 0) 0 else 1)
        requireMargin(price)
        val saved = ModelPriceTable.insert(price)
        logic.PriceHistoryLogic.record("create", null, saved, identity.id.toLongOrNull())
        return saved.id
    }

    @Put("/update")
    @Permission("gateway:price:update")
    suspend fun update(@Body request: SavePriceRequest, identity: neton.core.interfaces.Identity) {
        val current = ModelPriceTable.get(request.id)
            ?: throw HttpException(NetonErrorCode.RESOURCE_NOT_FOUND, GatewayErrorCodes.PRICE_NOT_FOUND)
        // 请求里没带 saleOverride（老前端、只改官方价的表单）= 保留原来的售价设置；传空串才是清除。
        // 以前这里直接写 null，管理台改一次官方价，单独设的售价就悄悄没了。
        val price = toModelPrice(request).let { p ->
            val kept = if (request.saleOverride == null) p.copy(saleOverride = current.saleOverride) else p
            if (request.tiers == null) logic.PricingTiers.withBaseFromTiers(kept.copy(tiers = current.tiers)) else kept
        }
        requireMargin(price)
        val next = current.copy(
            model = price.model,
            inputPrice = price.inputPrice, outputPrice = price.outputPrice,
            cacheReadPrice = price.cacheReadPrice, cacheWritePrice = price.cacheWritePrice,
            perRequestPrice = price.perRequestPrice, defaultMaxOutputTokens = price.defaultMaxOutputTokens,
            saleOverride = price.saleOverride, tiers = price.tiers,
            displayName = if (request.displayName == null) current.displayName else price.displayName,
            listed = request.listed?.let { if (it == 0) 0 else 1 } ?: current.listed,
        )
        ModelPriceTable.update(next)
        logic.PriceHistoryLogic.record("update", current, next, identity.id.toLongOrNull())
    }

    /**
     * 上架 / 下架：只有上架的模型对用户可见、可调用。下架不删价格（留在价格库里，随时可以再上架）。
     */
    @Put("/listed")
    @Permission("gateway:price:update")
    suspend fun setListed(@Query id: Long, @Query listed: Int, identity: neton.core.interfaces.Identity) {
        val current = ModelPriceTable.get(id)
            ?: throw HttpException(NetonErrorCode.RESOURCE_NOT_FOUND, GatewayErrorCodes.PRICE_NOT_FOUND)
        val next = current.copy(listed = if (listed == 0) 0 else 1)
        if (next.listed == current.listed) return
        ModelPriceTable.update(next)
        logic.PriceHistoryLogic.record(if (next.listed == 1) "list" else "unlist", current, next, identity.id.toLongOrNull())
    }

    /** 官方价（成本轨）+ 售价覆盖；售价低于毛利底线由 [logic.SalePricing.requireMargin] 拒绝。 */
    /** 批量导入：见 [logic.PriceImportLogic]。 */
    @Post("/import")
    @Permission("gateway:price:create")
    suspend fun import(@Body request: PriceImportRequest, identity: neton.core.interfaces.Identity): logic.PriceImportResult =
        importLogic.importItems(request.items.map { toItem(it) }, request.overwrite, operatorId = identity.id.toLongOrNull())

    /** 立即检查一次价源（定时任务 gateway-price-sync 每天也会做）：返回差异、记录检查结果，不改价。 */
    @Post("/sync")
    @Permission("gateway:price:query")
    suspend fun sync(): logic.CalibrationPreview = calibrationLogic.check()

    /** 最近一次价源检查结果。 */
    @Get("/sync/last")
    @Permission("gateway:price:query")
    suspend fun lastSync(): model.PriceSyncRun? = calibrationLogic.lastRun()

    private suspend fun requireMargin(price: ModelPrice) {
        val policy = logic.PricingPolicy.current()
        logic.SalePricing.requireMargin(price, policy.markup, policy.minMargin)
    }

    private fun toItem(r: SavePriceRequest) = logic.PriceImportItem(
        model = r.model, inputPrice = r.inputPrice, outputPrice = r.outputPrice,
        cacheReadPrice = r.cacheReadPrice, cacheWritePrice = r.cacheWritePrice,
        perRequestPrice = r.perRequestPrice, defaultMaxOutputTokens = r.defaultMaxOutputTokens, saleOverride = r.saleOverride,
        tiers = r.tiers,
    )

    private fun toModelPrice(request: SavePriceRequest): ModelPrice = ModelPrice(
        model = request.model,
        inputPrice = logic.PricingCalc.requirePrice(request.inputPrice, "inputPrice"),
        outputPrice = logic.PricingCalc.requirePrice(request.outputPrice, "outputPrice"),
        cacheReadPrice = logic.PricingCalc.requirePrice(request.cacheReadPrice, "cacheReadPrice"),
        cacheWritePrice = logic.PricingCalc.requirePrice(request.cacheWritePrice, "cacheWritePrice"),
        perRequestPrice = request.perRequestPrice, saleOverride = request.saleOverride?.trim()?.takeIf { it.isNotEmpty() },
        defaultMaxOutputTokens = request.defaultMaxOutputTokens?.takeIf { it > 0 },
        tiers = logic.PricingTiers.normalize(request.tiers),
        displayName = request.displayName?.trim()?.take(64)?.takeIf { it.isNotEmpty() },
    ).let { logic.PricingTiers.withBaseFromTiers(it) }

    @Delete("/delete/{id}")
    @Permission("gateway:price:delete")
    suspend fun delete(@PathVariable id: Long, identity: neton.core.interfaces.Identity) {
        val current = ModelPriceTable.get(id) ?: return
        ModelPriceTable.destroy(id)
        logic.PriceHistoryLogic.record("delete", current, null, identity.id.toLongOrNull())
    }

    /** 某个模型的调价记录（最近 50 条）。 */
    @Get("/history")
    @Permission("gateway:price:query")
    suspend fun history(@Query model: String): List<model.PriceHistory> = logic.PriceHistoryLogic.list(model)
}
