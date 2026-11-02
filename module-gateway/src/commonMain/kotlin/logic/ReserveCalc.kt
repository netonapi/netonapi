package logic

import kotlinx.serialization.json.*
import model.ModelPrice

/**
 * reserve 金额计算（纯函数，V004 §7.1）：请求前算出**保守上界**用于资金占位。
 *
 * 原则：宁可多预留（finalize 会释放差额），不可少预留（少预留 = 超支穿透）。
 * 无价格 / 无法确定输出上限 → **拒绝请求**，绝不免费放行。
 */
object ReserveCalc {

    sealed interface Result {
        /** [estimated] = 输入 token 用字节数保守估算（无 tokenizer） */
        data class Ok(val amount: Long, val estimated: Boolean) : Result
        /** 模型未配置定价 */
        data object NoPrice : Result
        /** 请求未传 max_tokens 且模型未配置 defaultMaxOutputTokens */
        data object NoOutputCap : Result
    }

    /**
     * @param requestBody 原始请求体（用于取 max_tokens 与估算输入规模）
     * @param price       模型定价；null = 未定价
     * @param groupRatio  分组倍率（decimal 串）
     */
    /**
     * @param inputUpperBytes 输入上界（字节）；null = 用 requestBody 的字节数
     * @param noOutput 没有输出侧 token 的端点（音频）：不要求输出上限，只按输入预留
     */
    fun compute(requestBody: String, raw: ModelPrice?, groupRatio: String, inputUpperBytes: Int? = null, noOutput: Boolean = false): Result {
        if (raw == null) return Result.NoPrice
        // 与扣费同一个实付单价（PricingCalc.charge）：预留是它的上界，口径不同就可能扣得比预留多
        val price = PricingCalc.scaledPrice(raw, groupRatio)

        // 按次计价：直接是上界
        price.perRequestPrice?.let { per ->
            return Result.Ok(per, estimated = false)
        }

        if (noOutput) {
            val inputUpper = inputUpperBytes ?: utf8Length(requestBody)
            return Result.Ok(PricingCalc.tokenCost(inputUpper, PricingCalc.parseMicro(price.inputPrice)), estimated = true)
        }

        val outputCap = outputCapOf(requestBody) ?: price.defaultMaxOutputTokens ?: return Result.NoOutputCap
        if (outputCap <= 0) return Result.NoOutputCap

        // 输入上界：无 tokenizer → 用 UTF-8 字节数（1 token 通常 ≥1 字节，故字节数是安全上界）
        val inputUpper = utf8Length(requestBody)
        // 保守：全部输入按最贵的 fresh input 价计（不假设有 cache 命中折扣）
        val cost = PricingCalc.tokenCost(inputUpper, PricingCalc.parseMicro(price.inputPrice)) +
            PricingCalc.tokenCost(outputCap, PricingCalc.parseMicro(price.outputPrice))
        return Result.Ok(cost, estimated = true)
    }

    /** UTF-8 字节数：只算不拷（请求体可能几十 KB，每个请求都为了数个数而复制一份不值）。 */
    fun utf8Length(s: String): Int {
        var n = 0; var i = 0
        while (i < s.length) {
            val c = s[i].code
            n += when {
                c < 0x80 -> 1
                c < 0x800 -> 2
                c in 0xD800..0xDBFF && i + 1 < s.length -> { i++; 4 }  // 代理对：一个 4 字节字符
                else -> 3
            }
            i++
        }
        return n
    }

    /** 三协议的输出上限字段：OpenAI max_tokens / max_completion_tokens、Anthropic max_tokens、Gemini generationConfig.maxOutputTokens。 */
    private fun outputCapOf(body: String): Int? = runCatching {
        val o = Json { ignoreUnknownKeys = true }.parseToJsonElement(body).jsonObject
        o["max_tokens"]?.jsonPrimitive?.intOrNull
            ?: o["max_completion_tokens"]?.jsonPrimitive?.intOrNull
            ?: o["max_output_tokens"]?.jsonPrimitive?.intOrNull  // Responses API
            ?: (o["generationConfig"] as? JsonObject)?.get("maxOutputTokens")?.jsonPrimitive?.intOrNull
    }.getOrNull()
}
