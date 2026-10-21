package logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.ModelPrice

/** 一档价格。[maxPromptTokens] 为本档覆盖的总输入 token 上限（含），null = 不封顶（只能是最后一档）。 */
@Serializable
data class PriceTier(
    val maxPromptTokens: Long?,
    val input: String,
    val output: String,
    val cacheRead: String = "0",
    val cacheWrite: String = "0",
)

/**
 * 按上下文长度分档的价格（如 Gemini 超过 200k 输入 token 后整体涨价）。
 *
 * 选档口径是**总输入 token**（新输入 + 缓存读 + 缓存写），与厂商长上下文门槛一致；
 * 恰好等于门槛落在低档。没有分档的价格行就是单档，所有函数对它原样返回。
 */
object PricingTiers {

    private val json = Json { ignoreUnknownKeys = true }

    /** 解析并校验；空白 → null。非法一律拒绝，不让坏的分档进入计费。 */
    fun parse(raw: String?): List<PriceTier>? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val tiers = runCatching { json.decodeFromString<List<PriceTier>>(s) }
            .getOrElse { rejectField("tiers", GatewayErrorCodes.PRICE_TIERS_INVALID) }
        if (tiers.size < 2) rejectField("tiers", GatewayErrorCodes.PRICE_TIERS_INVALID)
        var last = 0L
        tiers.forEachIndexed { i, t ->
            val isLast = i == tiers.lastIndex
            val max = t.maxPromptTokens
            if (isLast != (max == null)) rejectField("tiers", GatewayErrorCodes.PRICE_TIERS_INVALID)
            if (max != null) {
                if (max <= last) rejectField("tiers", GatewayErrorCodes.PRICE_TIERS_INVALID)
                last = max
            }
            listOf(t.input, t.output, t.cacheRead, t.cacheWrite).forEach {
                runCatching { PricingCalc.requirePrice(it, "tiers") }
                    .getOrElse { rejectField("tiers", GatewayErrorCodes.PRICE_TIERS_INVALID) }
            }
        }
        return tiers.map {
            it.copy(
                input = PricingCalc.requirePrice(it.input), output = PricingCalc.requirePrice(it.output),
                cacheRead = PricingCalc.requirePrice(it.cacheRead), cacheWrite = PricingCalc.requirePrice(it.cacheWrite),
            )
        }
    }

    fun render(tiers: List<PriceTier>): String = json.encodeToString(tiers)

    /** 规范化后的分档 JSON；空 → null。 */
    fun normalize(raw: String?): String? = parse(raw)?.let { render(it) }

    /** 按总输入 token 选档，返回单档价格（四个价格字段换成命中档，tiers 清空）。 */
    fun select(price: ModelPrice, totalInputTokens: Long): ModelPrice {
        val tiers = parse(price.tiers) ?: return price
        val hit = tiers.firstOrNull { it.maxPromptTokens == null || totalInputTokens <= it.maxPromptTokens } ?: tiers.last()
        return apply(price, hit)
    }

    /** 命中档的序号（0 起）；单档返回 0。写进定价快照，结算可解释。 */
    fun tierIndex(price: ModelPrice, totalInputTokens: Long): Int {
        val tiers = parse(price.tiers) ?: return 0
        return tiers.indexOfFirst { it.maxPromptTokens == null || totalInputTokens <= it.maxPromptTokens }.coerceAtLeast(0)
    }

    /** 最贵的一档（输入 + 输出价之和最大）：预留时还不知道实际输入多长，按它估上界。 */
    fun mostExpensive(price: ModelPrice): ModelPrice {
        val tiers = parse(price.tiers) ?: return price
        val top = tiers.maxBy { PricingCalc.parseMicro(it.input) + PricingCalc.parseMicro(it.output) }
        return apply(price, top)
    }

    /** 总输入 token：上游的 prompt 有的已含缓存读（OpenAI），有的不含（Anthropic），取大者避免重复计。 */
    fun totalInput(usage: Usage): Long =
        maxOf(usage.promptTokens, usage.cacheReadTokens).toLong() + usage.cacheWriteTokens

    private fun apply(price: ModelPrice, t: PriceTier): ModelPrice = price.copy(
        inputPrice = t.input, outputPrice = t.output, cacheReadPrice = t.cacheRead, cacheWritePrice = t.cacheWrite, tiers = null,
    )
}
