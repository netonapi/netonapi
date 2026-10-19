package logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import model.ModelPrice
import neton.database.dsl.*
import neton.logging.Logger
import table.ModelPriceTable

@Serializable
data class PriceImportItem(
    val model: String,
    val inputPrice: String = "0",
    val outputPrice: String = "0",
    val cacheReadPrice: String = "0",
    val cacheWritePrice: String = "0",
    val perRequestPrice: Long? = null,
    val defaultMaxOutputTokens: Int? = null,
    val saleOverride: String? = null,
)

@Serializable
data class PriceImportResult(
    val created: Int,
    val updated: Int,
    val skipped: Int,
    val errors: List<String> = emptyList(),
    /**
     * 因为「新官方价 + 已有的售价设置」会亏本而没更新的模型。官方涨价、而这个模型的售价是写死的，
     * 直接覆盖就是亏本卖 —— 留着旧价，等运营在价格页调完售价再同步。
     */
    val heldForLoss: List<String> = emptyList(),
)

/**
 * 价目表导入：管理台粘贴、`POST /admin/gateway/price/sync`、定时任务 gateway-price-sync 三处共用。
 * 先把全部条目校验一遍，有一条不合法就整批不写 —— 200 行写到第 137 行报错停下，剩下的哪些写了要对着数。
 *
 * 价源格式与管理台粘贴框一致：数组 `[{model,inputPrice,outputPrice,...}]`，或对象
 * `{"gpt-4o":{"input":2.5,"output":10,"cacheRead":1.25,"maxOutput":16384}}`。单位 USD / 1M tokens。
 */
@neton.core.annotations.Logic(logger = "logic.gateway-price-import")
class PriceImportLogic(private val log: Logger) {

    suspend fun importItems(items: List<PriceImportItem>, overwrite: Boolean, source: String = "import", operatorId: Long? = null): PriceImportResult {
        val errors = mutableListOf<String>()
        val policy = PricingPolicy.current()
        val parsed = items.mapIndexedNotNull { i, item ->
            if (item.model.isBlank()) { errors += "#${i + 1}: model 为空"; return@mapIndexedNotNull null }
            runCatching { toModelPrice(item.copy(model = item.model.trim())).also { SalePricing.requireMargin(it, policy.markup, policy.minMargin) } }
                .onFailure { errors += "#${i + 1} ${item.model}: ${it.message ?: it::class.simpleName}" }
                .getOrNull()
        }
        if (errors.isNotEmpty()) return PriceImportResult(0, 0, 0, errors)
        var created = 0; var updated = 0; var skipped = 0
        val held = mutableListOf<String>()
        // 价目表几百行，逐条查库是几百次往返；一次读全表建 model → 行的索引（定价表本身就是小表）
        val existing = ModelPriceTable.findAll().associateBy { it.model }
        for (price in parsed.distinctBy { it.model }) {
            val current = existing[price.model]
            when {
                current == null -> {
                    val saved = ModelPriceTable.insert(price.copy(source = source))
                    PriceHistoryLogic.record(source, null, saved, operatorId)
                    created++
                }
                !overwrite -> skipped++
                else -> {
                    val merged = current.copy(
                        inputPrice = price.inputPrice, outputPrice = price.outputPrice,
                        cacheReadPrice = price.cacheReadPrice, cacheWritePrice = price.cacheWritePrice,
                        perRequestPrice = price.perRequestPrice ?: current.perRequestPrice,
                        defaultMaxOutputTokens = price.defaultMaxOutputTokens ?: current.defaultMaxOutputTokens,
                        saleOverride = price.saleOverride ?: current.saleOverride,
                        source = source,
                    )
                    // 上面校验的是导入条目本身（它通常不带售价设置）；真正要写进去的是「新官方价 + 旧售价设置」，
                    // 这一行要再过一次毛利闸门，否则官方涨价时写死的售价会悄悄变成亏本价
                    if (runCatching { SalePricing.requireMargin(merged, policy.markup, policy.minMargin) }.isFailure) {
                        held += price.model
                        continue
                    }
                    ModelPriceTable.update(merged)
                    PriceHistoryLogic.record(source, current, merged, operatorId)
                    updated++
                }
            }
        }
        if (held.isNotEmpty()) log.warn("price import held models whose sale price would fall below cost: ${held.joinToString(",")}")
        log.info("price import source=$source created=$created updated=$updated skipped=$skipped held=${held.size}")
        return PriceImportResult(created, updated, skipped, heldForLoss = held)
    }

    /** 从 NEWGATE_PRICE_SYNC_URL 拉价目表并导入；未配置返回 null。 */
    suspend fun syncFromEnv(): PriceImportResult? {
        val url = neton.core.config.getEnv(ENV_SYNC_URL)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val overwrite = neton.core.config.getEnv(ENV_SYNC_OVERWRITE)?.trim()?.lowercase() != "false"
        // 本模块不带 HTTP 引擎，客户端能力来自应用绑定的 provider（与渠道转发同源）
        val provider = RelayEngine.deployConfigClients ?: return PriceImportResult(0, 0, 0, listOf("HttpClientProvider 未绑定"))
        val client = provider.create { connectMillis = 5_000; requestMillis = 30_000; socketMillis = 30_000 }
        try {
            val res = client.request(neton.http.client.HttpClientRequest(method = neton.http.client.HttpClientMethod.Get, url = url))
            if (res.statusCode !in 200..299) return PriceImportResult(0, 0, 0, listOf("价源 HTTP ${res.statusCode}: ${res.body.take(200)}"))
            val items: List<PriceImportItem> = try { parseSheet(res.body) } catch (e: Throwable) {
                return PriceImportResult(0, 0, 0, listOf("价源不是合法 JSON：${e.message}"))
            }
            return importItems(items, overwrite, source = "sync")
        } finally {
            runCatching { client.close() }
        }
    }

    fun parseSheet(text: String): List<PriceImportItem> {
        val root = Json.parseToJsonElement(text)
        fun pick(o: JsonObject, vararg keys: String): String? = keys.firstNotNullOfOrNull { k -> o[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull }
        fun fromObj(model: String, o: JsonObject) = PriceImportItem(
            model = model,
            inputPrice = pick(o, "inputPrice", "input", "prompt") ?: "0",
            outputPrice = pick(o, "outputPrice", "output", "completion") ?: "0",
            cacheReadPrice = pick(o, "cacheReadPrice", "cacheRead", "cache_read", "cached_input") ?: "0",
            cacheWritePrice = pick(o, "cacheWritePrice", "cacheWrite", "cache_write") ?: "0",
            perRequestPrice = pick(o, "perRequestPrice", "perRequest")?.toLongOrNull(),
            defaultMaxOutputTokens = pick(o, "defaultMaxOutputTokens", "maxOutput", "max_output_tokens")?.toIntOrNull(),
        )
        return when (root) {
            is JsonArray -> root.mapNotNull { e -> (e as? JsonObject)?.let { fromObj(it["model"]?.jsonPrimitive?.contentOrNull ?: "", it) } }
            is JsonObject -> root.entries.mapNotNull { (k, v) -> (v as? JsonObject)?.let { fromObj(k, it) } }
            else -> emptyList()
        }
    }

    private fun toModelPrice(i: PriceImportItem): ModelPrice = ModelPrice(
        model = i.model,
        inputPrice = PricingCalc.requirePrice(i.inputPrice, "inputPrice"),
        outputPrice = PricingCalc.requirePrice(i.outputPrice, "outputPrice"),
        cacheReadPrice = PricingCalc.requirePrice(i.cacheReadPrice, "cacheReadPrice"),
        cacheWritePrice = PricingCalc.requirePrice(i.cacheWritePrice, "cacheWritePrice"),
        perRequestPrice = i.perRequestPrice, saleOverride = i.saleOverride,
        defaultMaxOutputTokens = i.defaultMaxOutputTokens?.takeIf { it > 0 },
    )

    companion object {
        const val ENV_SYNC_URL = "NEWGATE_PRICE_SYNC_URL"
        const val ENV_SYNC_OVERWRITE = "NEWGATE_PRICE_SYNC_OVERWRITE"
    }
}
