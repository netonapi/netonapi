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

    /** 十进制格式校验：可选负号 + 数字 + 至多一个小数点；整数位 ≤ maxIntDigits。 */
    fun isValidDecimal(decimal: String, maxIntDigits: Int = 7): Boolean {
        val s = decimal.trim()
        val body = s.removePrefix("-")
        if (body.isEmpty()) return false
        val parts = body.split('.')
        if (parts.size > 2) return false
        val intPart = parts[0]
        val fracPart = parts.getOrElse(1) { "" }
        if (intPart.isEmpty() && fracPart.isEmpty()) return false
        if (!intPart.all { it.isDigit() } || !fracPart.all { it.isDigit() }) return false
        return intPart.trimStart('0').length <= maxIntDigits
    }

    /** 规范化表示："01.5000"→"1.5"、"1.0"→"1"、".5"→"0.5"——存储/比较统一形态。 */
    fun normalize(decimal: String): String {
        require(isValidDecimal(decimal)) { "invalid decimal: '$decimal'" }
        val s = decimal.trim()
        val neg = s.startsWith("-")
        val body = s.removePrefix("-")
        val parts = body.split('.')
        val intPart = parts[0].trimStart('0').ifEmpty { "0" }
        val fracPart = parts.getOrElse(1) { "" }.trimEnd('0')
        val n = if (fracPart.isEmpty()) intPart else "$intPart.$fracPart"
        return if (neg && n != "0") "-$n" else n
    }

    /** 价格字段校验（非负，≤7 位整数），返回规范化串；供 admin 写接口使用。 */
    fun requirePrice(decimal: String, field: String = "price"): String {
        require(isValidDecimal(decimal) && !decimal.trim().startsWith("-")) {
            "$field must be a non-negative decimal (got '$decimal')"
        }
        return normalize(decimal)
    }

    /** 倍率/折扣校验（(0,100]），返回规范化串。 */
    fun requireRatio(decimal: String, field: String = "ratio"): String {
        require(isValidDecimal(decimal, maxIntDigits = 3)) { "$field must be a decimal (got '$decimal')" }
        val v = parseScaled(decimal, 4)
        require(v in 1..1_000_000) { "$field must be in (0, 100] (got '$decimal')" }
        return normalize(decimal)
    }

    /** "3.5" → 3_500_000（μUSD/1M tokens，即小数 ×1e6，截断到 6 位小数）。 */
    fun parseMicro(decimal: String): Long = parseScaled(decimal, 6)

    /** "0.8" → 8000（万分位）。 */
    fun parseRatio(decimal: String): Int = parseScaled(decimal, 4).toInt()

    private fun parseScaled(decimal: String, scale: Int): Long {
        require(isValidDecimal(decimal)) { "invalid decimal: '$decimal'" }
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

    /** 溢出检查乘法：计费路径不得静默回绕。 */
    private fun mulExact(a: Long, b: Long): Long {
        if (a == 0L || b == 0L) return 0
        val r = a * b
        require(r / a == b) { "pricing overflow: $a * $b" }
        return r
    }

    private fun pow10(n: Int): Long {
        var v = 1L
        repeat(n) { v *= 10 }
        return v
    }

    /** tokens × (μUSD/1M tokens) / 1M，向上取整（tokens>0 时至少 1μUSD 由价格决定，不强加下限）。 */
    fun tokenCost(tokens: Int, priceMicroPer1M: Long): Long {
        if (tokens <= 0 || priceMicroPer1M <= 0) return 0
        return (mulExact(tokens.toLong(), priceMicroPer1M) + 999_999) / 1_000_000
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
        mulExact(rawAmount(usage, price), parseRatio(groupRatio).toLong()) / 10_000

    /** 成本：官方价 × 渠道折扣。 */
    fun cost(usage: Usage, price: ModelPrice, costDiscount: String): Long =
        mulExact(rawAmount(usage, price), parseRatio(costDiscount).toLong()) / 10_000
}
