package logic

import neton.database.dsl.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.roundToLong
import model.ModelPrice
import setting.GatewaySettingKeys
import table.ModelPriceTable

/** 价源里的一条官方价（USD / 1M tokens 的规范化串）；缺的字段为 null。 */
@Serializable
data class SourcePrice(
    val key: String,
    val input: String? = null,
    val output: String? = null,
    val cacheRead: String? = null,
    val cacheWrite: String? = null,
    val maxOutput: Int? = null,
    /** 分档价格；[tiersKnown] 为 false 时价源不提供分档信息（保留我们现有的分档）。 */
    val tiers: List<PriceTier>? = null,
    val tiersKnown: Boolean = false,
)

/** 价源解析结果：能用的条目，以及认不出计费写法的模型名（不写入，在预览里列出）。 */
data class ParsedSource(val entries: List<SourcePrice>, val unparsable: List<String> = emptyList())

@Serializable
data class CalibrationRow(
    val model: String,
    /** SAME 一致 / CHANGED 有变化 / NOT_FOUND 价源里没有 / NEW 渠道已承载但还没定价（价源里有）。 */
    val status: String,
    /** 价源里匹配上的条目名（如 openai/gpt-4o）。 */
    val sourceKey: String? = null,
    val currentInput: String? = null,
    val currentOutput: String? = null,
    val currentCacheRead: String? = null,
    val currentCacheWrite: String? = null,
    val sourceInput: String? = null,
    val sourceOutput: String? = null,
    val sourceCacheRead: String? = null,
    val sourceCacheWrite: String? = null,
    /** 价源里这个模型的计费写法认不出（status = UNPARSABLE）等说明。 */
    val note: String? = null,
    /** 各项里变化最大的相对幅度，万分位（+ 涨 / − 跌）。 */
    val changeBp: Int = 0,
    /** 按新官方价，这个模型现有的售价设置会低于最低毛利（应用时会被扣下）。 */
    val wouldLose: Boolean = false,
)

@Serializable
data class CalibrationPreview(
    val format: String,
    val url: String,
    val fetched: Int,
    val rows: List<CalibrationRow>,
    val error: String? = null,
)

@Serializable
data class CalibrationApplyRequest(val models: List<String>)

/**
 * 校准官方价：从公开价源（LiteLLM / OpenRouter / 自定义）拉价目表，逐个和我们的官方价比对。
 *
 * 只比对、不自动写：价源偶尔会错（单位、漏位），运营看一眼差异再点「应用」。
 * 应用走批量导入同一条路径 —— 同样的整批校验、同样的毛利闸门（写死售价的模型涨价会被扣下）、同样记调价历史。
 */
@neton.core.annotations.Logic(logger = "logic.gateway-price-calibration")
class PriceCalibrationLogic(
    private val log: neton.logging.Logger,
    private val importLogic: PriceImportLogic,
    private val channelLogic: ChannelLogic,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun preview(): CalibrationPreview {
        val (format, url) = source()
        val fetched = fetch(format, url)
        val parsed = fetched.getOrElse { return CalibrationPreview(format, url, 0, emptyList(), it.message) }
        val entries = parsed.entries
        val index = Index(entries)
        val unparsable = parsed.unparsable.toSet()
        fun isUnparsable(model: String) = model in unparsable || model.substringAfterLast('/') in unparsable.map { it.substringAfterLast('/') }
        val policy = PricingPolicy.current()
        val prices = ModelPriceTable.findAll().sortedBy { it.model }
        val rows = prices.map { p ->
            val src = index.find(p.model)
            if (src == null && isUnparsable(p.model)) return@map CalibrationRow(p.model, UNPARSABLE, note = "billing expression not supported",
                currentInput = p.inputPrice, currentOutput = p.outputPrice, currentCacheRead = p.cacheReadPrice, currentCacheWrite = p.cacheWritePrice)
            src ?: return@map CalibrationRow(p.model, NOT_FOUND, currentInput = p.inputPrice, currentOutput = p.outputPrice,
                    currentCacheRead = p.cacheReadPrice, currentCacheWrite = p.cacheWritePrice)
            val next = merge(p, src)
            val change = maxChangeBp(p, next)
            val lose = runCatching { SalePricing.requireMargin(next, policy.markup, policy.minMargin) }.isFailure
            CalibrationRow(
                model = p.model, status = if (change == 0 && same(p, next)) SAME else CHANGED, sourceKey = src.key,
                currentInput = p.inputPrice, currentOutput = p.outputPrice, currentCacheRead = p.cacheReadPrice, currentCacheWrite = p.cacheWritePrice,
                sourceInput = next.inputPrice, sourceOutput = next.outputPrice, sourceCacheRead = next.cacheReadPrice, sourceCacheWrite = next.cacheWritePrice,
                changeBp = change, wouldLose = lose,
            )
        }
        // 渠道里已经配了、但还没定价的模型：网关现在会拒绝它们（未定价不放行），价源里有就建议直接补上
        val priced = prices.map { it.model }.toSet()
        val suggestions = servedModels().filter { it !in priced }.sorted().mapNotNull { m ->
            val src = index.find(m) ?: return@mapNotNull null
            CalibrationRow(
                model = m, status = NEW, sourceKey = src.key,
                sourceInput = src.input ?: "0", sourceOutput = src.output ?: "0", sourceCacheRead = src.cacheRead ?: "0", sourceCacheWrite = src.cacheWrite ?: "0",
            )
        }
        return CalibrationPreview(format, url, entries.size, rows + suggestions)
    }

    /**
     * 定时检查（任务 gateway-price-sync，以及管理台「检查价源」）：等同预览，并记一行检查结果。
     * 不写价格表 —— 官方价决定售价，变动必须经运营在「校准官方价」里确认。
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    suspend fun check(): CalibrationPreview {
        val p = preview()
        runCatching {
            table.PriceSyncRunTable.insert(model.PriceSyncRun(
                checkedAt = kotlin.time.Clock.System.now().toEpochMilliseconds(), source = p.url.take(512),
                changed = p.rows.count { it.status == CHANGED }, newModels = p.rows.count { it.status == NEW },
                unparsable = p.rows.count { it.status == UNPARSABLE }, error = p.error,
            ))
        }.onFailure { log.warn("price sync run not recorded: ${it.message}") }
        if (p.error != null) log.error("gateway.price.check failed source=${p.url}: ${p.error}")
        else log.info("gateway.price.check changed=${p.rows.count { it.status == CHANGED }} new=${p.rows.count { it.status == NEW }}")
        return p
    }

    /** 最近一次检查结果；从没检查过为 null。 */
    suspend fun lastRun(): model.PriceSyncRun? =
        table.PriceSyncRunTable.query { orderBy(model.PriceSyncRun::id.desc()); limitOffset(1, 0) }.list().firstOrNull()

    suspend fun apply(models: List<String>, operatorId: Long?): PriceImportResult {
        val wanted = models.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return PriceImportResult(0, 0, 0)
        // 应用时重新拉一次：不信任前端回传的价格，写进去的一定是价源此刻的值
        val (format, url) = source()
        val entries = fetch(format, url).getOrElse { return PriceImportResult(0, 0, 0, listOf(it.message ?: "fetch failed")) }.entries
        val index = Index(entries)
        val current = ModelPriceTable.findAll().associateBy { it.model }
        val items = wanted.mapNotNull { m ->
            val src = index.find(m) ?: return@mapNotNull null
            val p = current[m]
            if (p != null) {
                val next = merge(p, src)
                // 价源明确给了分档信息才动分档：单档 → 传空串清掉，分档 → 传新分档；否则不传（保留）
                PriceImportItem(m, next.inputPrice, next.outputPrice, next.cacheReadPrice, next.cacheWritePrice,
                    tiers = if (src.tiersKnown) next.tiers.orEmpty() else null)
            } else {
                PriceImportItem(m, src.input ?: "0", src.output ?: "0", src.cacheRead ?: "0", src.cacheWrite ?: "0", defaultMaxOutputTokens = src.maxOutput,
                    tiers = src.tiers?.let { PricingTiers.render(it) })
            }
        }
        return importLogic.importItems(items, overwrite = true, source = "calibrate", operatorId = operatorId)
    }

    private suspend fun source(): Pair<String, String> {
        val settings = PricingPolicy.settings
        val format = settings?.get(GatewaySettingKeys.PRICING_SOURCE_FORMAT) ?: GatewaySettingKeys.PRICING_SOURCE_FORMAT.default
        val url = settings?.get(GatewaySettingKeys.PRICING_SOURCE_URL) ?: GatewaySettingKeys.PRICING_SOURCE_URL.default
        return format.trim().lowercase() to url.trim()
    }

    private suspend fun fetch(format: String, url: String): Result<ParsedSource> {
        val provider = RelayEngine.deployConfigClients ?: return Result.failure(IllegalStateException("HttpClientProvider 未绑定"))
        val client = provider.create { connectMillis = 5_000; requestMillis = 60_000; socketMillis = 60_000 }
        return try {
            val res = client.request(neton.http.client.HttpClientRequest(method = neton.http.client.HttpClientMethod.Get, url = url))
            if (res.statusCode !in 200..299) return Result.failure(IllegalStateException("价源 HTTP ${res.statusCode}"))
            runCatching { parse(format, res.body) }.onFailure { log.warn("price calibration source unparsable format=$format: ${it.message}") }
        } catch (e: Exception) {
            Result.failure(IllegalStateException("价源不可达：${e.message}"))
        } finally {
            runCatching { client.close() }
        }
    }

    fun parse(format: String, body: String): ParsedSource {
        val root = json.parseToJsonElement(body)
        return when (format) {
            "basellm" -> parseBasellm(root).let { (ok, bad) -> ParsedSource(ok, bad) }
            "models_dev" -> ParsedSource(parseModelsDev(root))
            "openrouter" -> ParsedSource(parseOpenRouter(root))
            "netonapi" -> ParsedSource(importLogic.parseSheet(body).map {
                SourcePrice(it.model, it.inputPrice, it.outputPrice, it.cacheReadPrice, it.cacheWritePrice, it.defaultMaxOutputTokens)
            })
            else -> ParsedSource(parseLiteLlm(root))
        }
    }

    /** 价源只覆盖它给出的字段；没给的（常见是缓存价）保留我们现在的值，不清零。 */
    private fun merge(p: ModelPrice, src: SourcePrice): ModelPrice = p.copy(
        inputPrice = src.input ?: p.inputPrice,
        outputPrice = src.output ?: p.outputPrice,
        cacheReadPrice = src.cacheRead ?: p.cacheReadPrice,
        cacheWritePrice = src.cacheWrite ?: p.cacheWritePrice,
        tiers = if (src.tiersKnown) src.tiers?.let { PricingTiers.render(it) } else p.tiers,
    ).let { PricingTiers.withBaseFromTiers(it) }

    private fun same(a: ModelPrice, b: ModelPrice): Boolean =
        listOf(a.inputPrice to b.inputPrice, a.outputPrice to b.outputPrice, a.cacheReadPrice to b.cacheReadPrice, a.cacheWritePrice to b.cacheWritePrice)
            .all { (x, y) -> PricingCalc.parseMicro(x) == PricingCalc.parseMicro(y) } &&
            PricingTiers.normalize(a.tiers) == PricingTiers.normalize(b.tiers)

    private fun maxChangeBp(a: ModelPrice, b: ModelPrice): Int =
        listOf(a.inputPrice to b.inputPrice, a.outputPrice to b.outputPrice, a.cacheReadPrice to b.cacheReadPrice, a.cacheWritePrice to b.cacheWritePrice)
            .map { (x, y) ->
                val old = PricingCalc.parseMicro(x); val new = PricingCalc.parseMicro(y)
                when {
                    old == new -> 0L
                    old == 0L -> 10_000L
                    else -> (new - old) * 10_000 / old
                }
            }
            .maxByOrNull { abs(it) }?.coerceIn(-1_000_000, 1_000_000)?.toInt() ?: 0

    private suspend fun servedModels(): Set<String> = channelLogic.listEnabled().flatMap { ch ->
        val listed = ch.models.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val mapped = ch.modelMapping?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject.keys }.getOrNull() }.orEmpty()
        listed + mapped
    }.toSet()

    /** 模型名 → 价源条目：先精确匹配，再按去掉厂商前缀的名字匹配（同名多条取 key 最短的，结果确定）。 */
    private class Index(entries: List<SourcePrice>) {
        private val exact = entries.associateBy { it.key }
        private val bare = entries.groupBy { it.key.substringAfterLast('/') }
            .mapValues { (_, list) -> list.minWith(compareBy<SourcePrice> { it.key.length }.thenBy { it.key }) }
        fun find(model: String): SourcePrice? = exact[model] ?: bare[model] ?: bare[model.substringAfterLast('/')]
    }

    companion object {
        /** LiteLLM：`{model: {input_cost_per_token, output_cost_per_token, cache_read_input_token_cost, ...}}`，每 token 美元价。 */
        fun parseLiteLlm(root: JsonElement): List<SourcePrice> = root.jsonObject.entries.mapNotNull { (key, v) ->
            if (key == "sample_spec") return@mapNotNull null
            val o = v as? JsonObject ?: return@mapNotNull null
            val input = perToken(o["input_cost_per_token"])
            val output = perToken(o["output_cost_per_token"])
            if (input == null && output == null) return@mapNotNull null
            SourcePrice(
                key = key, input = input, output = output,
                cacheRead = perToken(o["cache_read_input_token_cost"]),
                cacheWrite = perToken(o["cache_creation_input_token_cost"]),
                maxOutput = (o["max_output_tokens"] as? JsonPrimitive)?.intOrNull,
            )
        }

        /** OpenRouter：`{data: [{id: "openai/gpt-4o", pricing: {prompt, completion, input_cache_read, input_cache_write}}]}`。 */
        fun parseOpenRouter(root: JsonElement): List<SourcePrice> =
            (root.jsonObject["data"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val p = o["pricing"] as? JsonObject ?: return@mapNotNull null
                val input = perToken(p["prompt"])
                val output = perToken(p["completion"])
                // OpenRouter 用 -1 表示「价格可变」（路由类模型），这种拿来当官方价没有意义
                if (input == null && output == null) return@mapNotNull null
                SourcePrice(id, input, output, perToken(p["input_cache_read"]), perToken(p["input_cache_write"]))
            }

        /** 每 token 美元价（数字或字符串）→ 每 1M tokens 的规范化价格串；负数、非数字为 null。 */
        private fun perToken(e: JsonElement?): String? {
            val prim = e as? JsonPrimitive ?: return null
            if (prim is JsonNull) return null
            val v = prim.doubleOrNull ?: prim.contentOrNull?.toDoubleOrNull() ?: return null
            if (v < 0 || v.isNaN() || v.isInfinite()) return null
            // ×1e6 得到每 1M 的美元价，再按 μUSD 精度（6 位小数）取整，避免 3e-06 这类浮点尾巴
            return PricingCalc.microToDecimal((v * 1e12).roundToLong())
        }

        /**
         * basellm/llm-metadata（new-api 官方倍率预设）：`{success, data:{billing_mode:{m:"tiered_expr"}, billing_expr:{m:"tier(...)"}}}`。
         * 返回（能解析的条目，认不出写法的模型名）。
         */
        fun parseBasellm(root: JsonElement): Pair<List<SourcePrice>, List<String>> {
            val data = (root.jsonObject["data"] as? JsonObject) ?: root.jsonObject
            val exprs = data["billing_expr"] as? JsonObject ?: return emptyList<SourcePrice>() to emptyList()
            val ok = mutableListOf<SourcePrice>(); val bad = mutableListOf<String>()
            for ((model, v) in exprs) {
                val expr = (v as? JsonPrimitive)?.contentOrNull
                val p = expr?.let { BillingExprParser.parse(it).getOrNull() }
                if (p == null) { bad += model; continue }
                ok += SourcePrice(model, p.base.input, p.base.output, p.base.cacheRead, p.base.cacheWrite, tiers = p.tiers, tiersKnown = true)
            }
            return ok to bad
        }

        /** models.dev `/api.json`：`{provider:{models:{id:{cost:{input,output,cache_read,cache_write}}}}}`，USD / 1M。同名模型优先取厂商自营的那条。 */
        fun parseModelsDev(root: JsonElement): List<SourcePrice> {
            val byModel = mutableMapOf<String, Pair<String, SourcePrice>>()
            for ((provider, pv) in root.jsonObject.entries.sortedBy { it.key }) {
                val models = (pv as? JsonObject)?.get("models") as? JsonObject ?: continue
                for ((id, mv) in models) {
                    val cost = (mv as? JsonObject)?.get("cost") as? JsonObject ?: continue
                    val input = perMillion(cost["input"]); val output = perMillion(cost["output"])
                    if (input == null && output == null) continue
                    val sp = SourcePrice(id, input, output, perMillion(cost["cache_read"]), perMillion(cost["cache_write"]))
                    val cur = byModel[id]
                    if (cur == null || (provider in FIRST_PARTY && cur.first !in FIRST_PARTY)) byModel[id] = provider to sp
                }
            }
            return byModel.values.map { it.second }
        }

        private val FIRST_PARTY = setOf("openai", "anthropic", "google", "deepseek")

        /** 每 1M 美元价（数字）→ 规范化价格串；负数、非数字为 null。 */
        private fun perMillion(e: JsonElement?): String? {
            val v = (e as? JsonPrimitive)?.doubleOrNull ?: return null
            if (v < 0 || v.isNaN() || v.isInfinite()) return null
            return PricingCalc.microToDecimal((v * 1e6).roundToLong())
        }

        const val UNPARSABLE = "UNPARSABLE"
        const val SAME = "SAME"
        const val CHANGED = "CHANGED"
        const val NOT_FOUND = "NOT_FOUND"
        const val NEW = "NEW"
    }
}
