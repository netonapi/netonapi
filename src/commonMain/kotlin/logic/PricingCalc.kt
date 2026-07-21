package logic

import model.ModelPrice

/** 上游回报的用量。 */
data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
)

/**
 * μUSD 计费纯函数（spec 决策 B/B2）。
 * 价格 = decimal 字符串（USD/1M tokens）→ parseMicro → μUSD/1M tokens（Long）。
 * 倍率/折扣 = decimal 字符串 → parseRatio → 万分位整数。全程 Long，token 费向上取整。
 */
object PricingCalc {

    /** "3.5" → 3_500_000（μUSD/1M tokens，即小数 ×1e6，截断到 6 位小数）。 */
    fun parseMicro(decimal: String): Long = parseScaled(decimal, 6)

    /** "0.8" → 8000（万分位）。 */
    fun parseRatio(decimal: String): Int = parseScaled(decimal, 4).toInt()

    private fun parseScaled(decimal: String, scale: Int): Long {
        val s = decimal.trim()
        val neg = s.startsWith("-")
        val body = s.removePrefix("-")
        val dot = body.indexOf('.')
        val intPart = if (dot < 0) body else body.take(dot)
        val fracPart = if (dot < 0) "" else body.drop(dot + 1)
        val frac = (fracPart + "0".repeat(scale)).take(scale)
        val value = (intPart.ifEmpty { "0" }).toLong() * pow10(scale) + (frac.ifEmpty { "0" }).toLong()
        return if (neg) -value else value
    }

    private fun pow10(n: Int): Long {
        var v = 1L
        repeat(n) { v *= 10 }
        return v
    }

    /** tokens × (μUSD/1M tokens) / 1M，向上取整（tokens>0 时至少 1μUSD 由价格决定，不强加下限）。 */
    fun tokenCost(tokens: Int, priceMicroPer1M: Long): Long {
        if (tokens <= 0 || priceMicroPer1M <= 0) return 0
        return (tokens.toLong() * priceMicroPer1M + 999_999) / 1_000_000
    }

    private fun rawAmount(usage: Usage, price: ModelPrice): Long {
        price.perRequestPrice?.let { return it }
        val cacheRead = usage.cacheReadTokens.coerceAtMost(usage.promptTokens)
        val freshInput = usage.promptTokens - cacheRead
        return tokenCost(freshInput, parseMicro(price.inputPrice)) +
            tokenCost(cacheRead, parseMicro(price.cacheReadPrice)) +
            tokenCost(usage.cacheWriteTokens, parseMicro(price.cacheWritePrice)) +
            tokenCost(usage.completionTokens, parseMicro(price.outputPrice))
    }

    /** 收入：售价 × 分组倍率。 */
    fun charge(usage: Usage, price: ModelPrice, groupRatio: String): Long =
        rawAmount(usage, price) * parseRatio(groupRatio) / 10_000

    /** 成本：官方价 × 渠道折扣。 */
    fun cost(usage: Usage, price: ModelPrice, costDiscount: String): Long =
        rawAmount(usage, price) * parseRatio(costDiscount) / 10_000
}
