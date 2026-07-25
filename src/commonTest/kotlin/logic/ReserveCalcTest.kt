package logic

import model.ModelPrice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReserveCalcTest {

    private fun price(
        input: String = "0", output: String = "0",
        perRequest: Long? = null, defaultCap: Int? = null,
    ) = ModelPrice(
        model = "m", inputPrice = input, outputPrice = output,
        perRequestPrice = perRequest, defaultMaxOutputTokens = defaultCap,
    )

    @Test fun unpriced_model_is_rejected_not_free() {
        assertEquals(ReserveCalc.Result.NoPrice, ReserveCalc.compute("{}", null, "1"))
    }

    @Test fun missing_output_cap_is_rejected() {
        // 请求无 max_tokens 且模型无 defaultMaxOutputTokens → 无法算安全上界 → 拒绝
        val r = ReserveCalc.compute("""{"model":"m"}""", price(input = "1", output = "2"), "1")
        assertEquals(ReserveCalc.Result.NoOutputCap, r)
    }

    @Test fun per_request_price_needs_no_output_cap() {
        val r = ReserveCalc.compute("{}", price(perRequest = 5000), "1")
        assertEquals(ReserveCalc.Result.Ok(5000, estimated = false), r)
    }

    @Test fun per_request_price_applies_group_ratio() {
        val r = ReserveCalc.compute("{}", price(perRequest = 5000), "2") as ReserveCalc.Result.Ok
        assertEquals(10_000, r.amount)
    }

    @Test fun token_price_uses_request_max_tokens() {
        // output=1000 tokens × 3 USD/1M = 3000 μUSD；input 按字节数上界
        val body = """{"max_tokens":1000}"""
        val r = ReserveCalc.compute(body, price(input = "0", output = "3", defaultCap = 10), "1") as ReserveCalc.Result.Ok
        assertEquals(3000, r.amount)
        assertTrue(r.estimated, "无 tokenizer 时应标记为估算")
    }

    @Test fun request_max_tokens_overrides_model_default() {
        val small = ReserveCalc.compute("""{"max_tokens":10}""", price(output = "3", defaultCap = 100_000), "1") as ReserveCalc.Result.Ok
        val large = ReserveCalc.compute("""{"max_tokens":100000}""", price(output = "3", defaultCap = 10), "1") as ReserveCalc.Result.Ok
        assertTrue(small.amount < large.amount, "应以请求值为准：small=${small.amount} large=${large.amount}")
    }

    @Test fun falls_back_to_model_default_cap() {
        val r = ReserveCalc.compute("{}", price(output = "3", defaultCap = 1000), "1") as ReserveCalc.Result.Ok
        assertEquals(3000, r.amount)
    }

    @Test fun anthropic_and_gemini_output_caps_are_read() {
        val anthropic = ReserveCalc.compute("""{"max_tokens":1000}""", price(output = "3"), "1")
        assertTrue(anthropic is ReserveCalc.Result.Ok)
        val gemini = ReserveCalc.compute(
            """{"generationConfig":{"maxOutputTokens":1000}}""", price(output = "3"), "1")
        assertTrue(gemini is ReserveCalc.Result.Ok, "Gemini generationConfig.maxOutputTokens 应被识别")
        assertEquals((anthropic as ReserveCalc.Result.Ok).amount, (gemini as ReserveCalc.Result.Ok).amount)
    }

    @Test fun openai_max_completion_tokens_is_read() {
        val r = ReserveCalc.compute("""{"max_completion_tokens":1000}""", price(output = "3"), "1")
        assertTrue(r is ReserveCalc.Result.Ok)
        assertEquals(3000, (r as ReserveCalc.Result.Ok).amount)
    }

    @Test fun input_bound_is_conservative_not_zero() {
        // 长请求体应抬高预留（字节数作输入上界），否则大 prompt 会预留不足
        val short = ReserveCalc.compute("""{"max_tokens":10,"p":"x"}""", price(input = "1000", output = "0"), "1") as ReserveCalc.Result.Ok
        val long = ReserveCalc.compute("""{"max_tokens":10,"p":"${"x".repeat(5000)}"}""", price(input = "1000", output = "0"), "1") as ReserveCalc.Result.Ok
        assertTrue(long.amount > short.amount, "长 prompt 必须预留更多：short=${short.amount} long=${long.amount}")
    }

    @Test fun malformed_body_falls_back_to_model_default() {
        val r = ReserveCalc.compute("not json", price(output = "3", defaultCap = 100), "1")
        assertTrue(r is ReserveCalc.Result.Ok, "坏 body 应回退到模型默认上限而非崩溃")
    }

    @Test fun zero_cap_is_rejected() {
        assertEquals(ReserveCalc.Result.NoOutputCap,
            ReserveCalc.compute("""{"max_tokens":0}""", price(output = "3"), "1"))
    }
}
