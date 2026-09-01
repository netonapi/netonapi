package logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import model.ModelPrice
import neton.core.http.ValidationError
import neton.core.http.ValidationException

/**
 * 售价轨解析（双轨计价，spec 决策 B2）。
 *
 * 两条轨道必须分清，否则毛利无从核算：
 *  - **成本轨** = 模型官方价 × 渠道折扣（`gateway_model_prices` 存的就是官方价）；
 *  - **售价轨** = `saleOverride` 逐字段覆盖，缺省字段 = 官方价 × 全局加价率（`NEWGATE_SALE_MARKUP`）。
 *
 * reserve 与 finalize 全按售价轨收钱、按成本轨记账。充值赠送/返利只动钱包台账，
 * 绝不进请求计价——每一笔毛利都必须能从快照解释出来。
 */
object SalePricing {

    const val ENV_MARKUP = "NEWGATE_SALE_MARKUP"
    const val ENV_MIN_MARGIN = "NEWGATE_MIN_MARGIN"

    /** 全局加价倍率（decimal 串，(0,100]）；未配置 = "1"。配置非法直接抛——账务不允许静默猜价。 */
    fun markup(): String = ratioEnv(ENV_MARKUP) ?: "1"

    /** 毛利下限倍率（decimal 串，(0,100]）；未配置 = "1"，即售价不得低于官方成本。 */
    fun minMargin(): String = ratioEnv(ENV_MIN_MARGIN) ?: "1"

    private fun ratioEnv(name: String): String? =
        neton.core.config.getEnv(name)?.takeIf { it.isNotBlank() }
            ?.let { PricingCalc.requireRatio(it, name) }

    /**
     * 官方价 → 售价。任一价格字段非法（含 `saleOverride` 结构/数值非法）直接抛
     * [ValidationException]：入口 fail-fast，绝不让坏价进 reserve/结算。
     */
    fun resolve(official: ModelPrice, markup: String = markup()): ModelPrice {
        PricingCalc.requirePrice(official.inputPrice, "inputPrice")
        PricingCalc.requirePrice(official.outputPrice, "outputPrice")
        PricingCalc.requirePrice(official.cacheReadPrice, "cacheReadPrice")
        PricingCalc.requirePrice(official.cacheWritePrice, "cacheWritePrice")
        val o = parseOverride(official.saleOverride)
        fun field(key: String, officialValue: String): String =
            o?.get(key)?.jsonPrimitive?.contentOrNull
                ?.let { PricingCalc.requirePrice(it, "saleOverride.$key") }
                ?: PricingCalc.scalePrice(officialValue, markup)
        // perRequest：override 显式给 null = 关闭按次计价；未给该键 = 官方按次价 × 加价率
        val perRequest = if (o != null && o.containsKey("perRequest")) {
            val p = o["perRequest"]?.jsonPrimitive
                ?: invalid("saleOverride.perRequest", "must be a non-negative integer or null")
            if (p is JsonNull) null
            else p.longOrNull?.takeIf { it >= 0 }
                ?: invalid("saleOverride.perRequest", "must be a non-negative integer or null")
        } else official.perRequestPrice?.let { scaleMicro(it, markup) }
        return ModelPrice(
            model = official.model,
            inputPrice = field("input", official.inputPrice),
            outputPrice = field("output", official.outputPrice),
            cacheReadPrice = field("cacheRead", official.cacheReadPrice),
            cacheWritePrice = field("cacheWrite", official.cacheWritePrice),
            perRequestPrice = perRequest,
            defaultMaxOutputTokens = official.defaultMaxOutputTokens,
        )
    }

    /**
     * 发布闸门（毛利底线）：售价任一维度低于「官方成本 × 毛利下限倍率」→ 拒绝发布。
     *
     * 实际成本 = 官方价 × 渠道折扣；折扣 ≤1 时官方价即成本上界，故此闸门是保守下界。
     * 折扣 >1 的渠道（转售加价）与倍率 <1 的用户组会把毛利压穿，由运行时 margin inversion
     * 告警兜底（见 RelayEngine.bill）——闸门挡的是「配错价」，不是「经营策略」。
     */
    fun requireMargin(price: ModelPrice) {
        val sale = resolve(price)
        val floor = minMargin()
        fun check(saleMicro: Long, officialMicro: Long, field: String) {
            if (saleMicro < scaleMicro(officialMicro, floor))
                invalid(field, "sale price must not be below the cost floor (official cost x $floor)")
        }
        check(PricingCalc.parseMicro(sale.inputPrice), PricingCalc.parseMicro(price.inputPrice), "saleOverride.input")
        check(PricingCalc.parseMicro(sale.outputPrice), PricingCalc.parseMicro(price.outputPrice), "saleOverride.output")
        check(PricingCalc.parseMicro(sale.cacheReadPrice), PricingCalc.parseMicro(price.cacheReadPrice), "saleOverride.cacheRead")
        check(PricingCalc.parseMicro(sale.cacheWritePrice), PricingCalc.parseMicro(price.cacheWritePrice), "saleOverride.cacheWrite")
        val sp = sale.perRequestPrice
        val op = price.perRequestPrice
        if (sp != null && op != null) check(sp, op, "saleOverride.perRequest")
    }

    private fun scaleMicro(micro: Long, ratio: String): Long {
        val r = PricingCalc.parseRatio(ratio).toLong()
        if (micro == 0L || r == 0L) return 0
        val prod = micro * r
        require(prod / micro == r) { "pricing scale overflow: $micro * $r" }
        return prod / 10_000
    }

    private fun parseOverride(json: String?): JsonObject? {
        val s = json?.trim().orEmpty()
        if (s.isEmpty()) return null
        return runCatching { Json.parseToJsonElement(s).jsonObject }
            .getOrElse { invalid("saleOverride", "must be a JSON object") }
    }

    private fun invalid(field: String, msg: String): Nothing =
        throw ValidationException(listOf(ValidationError(path = field, message = msg)))
}
