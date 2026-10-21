package logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToLong
import model.ModelPrice

/** 上游报的一个模型的价格（上游的模型名；USD / 1M tokens 的规范化串；按次为 μUSD）。 */
data class UpstreamPrice(
    val upstreamModel: String,
    val input: String,
    val output: String,
    val cacheRead: String,
    val cacheWrite: String,
    val perRequest: Long?,
)

/**
 * 上游价目接口解析（design §4.3）。纯函数：拉取与写库在 ChannelCostSyncLogic。
 *
 * - newapi：new-api / one-api 的 `/api/pricing`（`data[]`）或 `/api/ratio_config`（`data.model_ratio` 等映射表）。
 *   倍率 1 = $0.002 / 1K tokens，所以输入价 = model_ratio × 2（USD / 1M），输出 = 输入 × completion_ratio，
 *   缓存读 / 写 = 输入 × cache_ratio / create_cache_ratio；quota_type = 1 为按次，model_price 美元 / 次。
 * - openrouter：`/v1/models` 的 `pricing.*` 每 token 美元价；-1（可变价）跳过。
 *
 * 浮点只出现在这里的一次换算（按 μUSD 取整成价格串），之后全是整数运算。
 */
object UpstreamPricingParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(format: String, body: String): Result<List<UpstreamPrice>> = runCatching {
        val root = runCatching { json.parseToJsonElement(body) }.getOrElse { error("upstream pricing is not JSON") }
        when (format) {
            "newapi" -> parseNewApi(root)
            "openrouter" -> parseOpenRouter(root)
            else -> error("unknown pricing format $format")
        }.filter { valid(it) }
    }

    /** 价格串必须过 requirePrice（非负、整数部分 ≤ 7 位）：上游的怪数据跳过，不进入结算路径。 */
    private fun valid(p: UpstreamPrice): Boolean = runCatching {
        listOf(p.input, p.output, p.cacheRead, p.cacheWrite).forEach { PricingCalc.requirePrice(it) }
        require((p.perRequest ?: 0) in 0..1_000_000_000_000L)
    }.isSuccess

    /** × 上游分组倍率 × 上游额度单价：上游标价 → 我们实际付出的美元。 */
    fun scale(p: UpstreamPrice, groupRatio: String, creditRate: String): UpstreamPrice {
        fun s(v: String) = PricingCalc.scalePrice(PricingCalc.scalePrice(v, groupRatio), creditRate)
        return p.copy(
            input = s(p.input), output = s(p.output), cacheRead = s(p.cacheRead), cacheWrite = s(p.cacheWrite),
            perRequest = p.perRequest?.let { SalePricing.scaleMicro(SalePricing.scaleMicro(it, groupRatio), creditRate) },
        )
    }

    /**
     * 上游只报单档价、官方价分档时，按官方各档相对基础档的比例放大上游价，得到同结构的分档成本；
     * 避免长上下文按基础档低估成本。官方没有分档 → null。官方某项基础价为 0 时该项各档都取上游基础价。
     */
    fun withOfficialTiers(base: UpstreamPrice, official: ModelPrice?): List<PriceTier>? {
        val tiers = PricingTiers.parse(official?.tiers) ?: return null
        val first = tiers.first()
        fun ratio(up: String, officialBase: String, officialTier: String): String {
            val b = PricingCalc.parseMicro(officialBase)
            if (b == 0L) return up
            val u = PricingCalc.parseMicro(up)
            val t = PricingCalc.parseMicro(officialTier)
            return PricingCalc.microToDecimal(u * t / b)
        }
        return tiers.map { t ->
            PriceTier(
                maxPromptTokens = t.maxPromptTokens,
                input = ratio(base.input, first.input, t.input),
                output = ratio(base.output, first.output, t.output),
                cacheRead = ratio(base.cacheRead, first.cacheRead, t.cacheRead),
                cacheWrite = ratio(base.cacheWrite, first.cacheWrite, t.cacheWrite),
            )
        }
    }

    private fun parseNewApi(root: JsonElement): List<UpstreamPrice> {
        val o = root as? JsonObject ?: error("unexpected upstream pricing shape")
        val success = (o["success"] as? JsonPrimitive)?.booleanOrNull
        if (success != true) error("upstream pricing refused: ${(o["message"] as? JsonPrimitive)?.contentOrNull ?: "success=false"}")
        return when (val data = o["data"]) {
            is JsonArray -> data.mapNotNull { e -> (e as? JsonObject)?.let { pricingItem(it) } }
            is JsonObject -> ratioConfig(data)
            else -> error("upstream pricing has no data")
        }
    }

    /** `/api/pricing` 的一项。 */
    private fun pricingItem(o: JsonObject): UpstreamPrice? {
        val name = (o["model_name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        if ((o["quota_type"] as? JsonPrimitive)?.intOrNull == 1) {
            val usd = num(o["model_price"]) ?: return null
            return UpstreamPrice(name, "0", "0", "0", "0", (usd * 1_000_000).roundToLong())
        }
        val ratio = num(o["model_ratio"]) ?: return null
        return fromRatios(name, ratio, num(o["completion_ratio"]) ?: 1.0, num(o["cache_ratio"]), num(o["create_cache_ratio"]))
    }

    /** `/api/ratio_config`：各倍率是 model → 值 的映射表。 */
    private fun ratioConfig(d: JsonObject): List<UpstreamPrice> {
        fun map(key: String): Map<String, Double> =
            (d[key] as? JsonObject)?.mapNotNull { (k, v) -> num(v)?.let { k to it } }?.toMap().orEmpty()
        val model = map("model_ratio"); val completion = map("completion_ratio")
        val cache = map("cache_ratio"); val create = map("create_cache_ratio"); val price = map("model_price")
        val token = model.map { (m, r) -> fromRatios(m, r, completion[m] ?: 1.0, cache[m], create[m]) }
        val perRequest = price.filterKeys { it !in model }.map { (m, usd) -> UpstreamPrice(m, "0", "0", "0", "0", (usd * 1_000_000).roundToLong()) }
        return token + perRequest
    }

    private fun fromRatios(name: String, modelRatio: Double, completion: Double, cache: Double?, createCache: Double?): UpstreamPrice {
        val inputMicro = (modelRatio * 2 * 1_000_000).roundToLong()
        fun times(r: Double?) = if (r == null) "0" else PricingCalc.microToDecimal((inputMicro * r).roundToLong())
        // 没配缓存倍率时按 new-api 的默认：缓存读 1（按输入原价）、缓存写 1.25（setting/ratio_setting/cache_ratio.go）
        return UpstreamPrice(name, PricingCalc.microToDecimal(inputMicro), times(completion), times(cache ?: 1.0), times(createCache ?: 1.25), null)
    }

    private fun parseOpenRouter(root: JsonElement): List<UpstreamPrice> {
        val data = root.jsonObject["data"] as? JsonArray ?: error("openrouter models has no data")
        return data.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val p = o["pricing"] as? JsonObject ?: return@mapNotNull null
            val input = perToken(p["prompt"]); val output = perToken(p["completion"])
            if (input == null || output == null) return@mapNotNull null
            UpstreamPrice(id, input, output, perToken(p["input_cache_read"]) ?: "0", perToken(p["input_cache_write"]) ?: "0", null)
        }
    }

    private fun num(e: JsonElement?): Double? {
        val p = e as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        val v = p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull() ?: return null
        return v.takeIf { it >= 0 && !it.isNaN() && !it.isInfinite() }
    }

    private fun perToken(e: JsonElement?): String? = num(e)?.let { PricingCalc.microToDecimal((it * 1e12).roundToLong()) }
}
