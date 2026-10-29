package logic

import model.ModelPrice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpstreamPricingParserTest {

    private fun one(format: String, body: String) = UpstreamPricingParser.parse(format, body).getOrThrow().single()

    @Test
    fun newApiRatioToPrice() {
        val p = one("newapi", """{"success":true,"data":[{"model_name":"up-a","quota_type":0,"model_ratio":1.25,"completion_ratio":8,"cache_ratio":0.1,"model_price":0}]}""")
        assertEquals("up-a", p.upstreamModel)
        assertEquals("2.5", p.input)
        assertEquals("20", p.output)
        assertEquals("0.25", p.cacheRead)
        assertEquals("3.125", p.cacheWrite, "没给 create_cache_ratio → new-api 默认 1.25")
        assertNull(p.perRequest)
    }

    @Test
    fun newApiPerRequest() {
        val p = one("newapi", """{"success":true,"data":[{"model_name":"img","quota_type":1,"model_ratio":0,"model_price":0.04}]}""")
        assertEquals(40000L, p.perRequest)
    }

    @Test
    fun ratioConfigShape() {
        val p = one("newapi", """{"success":true,"data":{"model_ratio":{"up-b":0.5},"completion_ratio":{"up-b":4},"cache_ratio":{"up-b":0.25}}}""")
        assertEquals("1", p.input)
        assertEquals("4", p.output)
        assertEquals("0.25", p.cacheRead)
    }

    @Test
    fun successFalseFails() {
        val r = UpstreamPricingParser.parse("newapi", """{"success":false,"message":"无权访问"}""")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("无权访问"))
    }

    @Test
    fun htmlBodyFails() {
        assertTrue(UpstreamPricingParser.parse("newapi", "<html>502 Bad Gateway</html>").isFailure)
    }

    @Test
    fun openRouterPerToken() {
        val p = one("openrouter", """{"data":[{"id":"anthropic/claude-x","pricing":{"prompt":"0.000003","completion":"0.000015","input_cache_read":"0.0000003"}},{"id":"auto","pricing":{"prompt":"-1","completion":"-1"}}]}""")
        assertEquals("anthropic/claude-x", p.upstreamModel)
        assertEquals("3", p.input)
        assertEquals("15", p.output)
        assertEquals("0.3", p.cacheRead)
    }

    @Test
    fun scaleMultipliesGroupAndCredit() {
        val p = UpstreamPrice("up-a", "2.5", "20", "0", "0", 40000)
        val s = UpstreamPricingParser.scale(p, groupRatio = "0.8", creditRate = "0.14")
        assertEquals("0.28", s.input)
        assertEquals("2.24", s.output)
        assertEquals(4480L, s.perRequest)
    }

    @Test
    fun officialTiersScaleProportionally() {
        val official = ModelPrice(
            model = "g", inputPrice = "1.25", outputPrice = "10",
            tiers = """[{"maxPromptTokens":200000,"input":"1.25","output":"10","cacheRead":"0","cacheWrite":"0"},
                        {"maxPromptTokens":null,"input":"2.5","output":"15","cacheRead":"0","cacheWrite":"0"}]""",
        )
        val tiers = UpstreamPricingParser.withOfficialTiers(UpstreamPrice("g", "1", "8", "0", "0", null), official)!!
        assertEquals(200000L, tiers[0].maxPromptTokens)
        assertEquals("1", tiers[0].input)
        assertEquals("2", tiers[1].input)
        assertEquals("12", tiers[1].output)
        assertNull(UpstreamPricingParser.withOfficialTiers(UpstreamPrice("g", "1", "8", "0", "0", null), official.copy(tiers = null)))
    }

    @Test
    fun missingCacheRatiosUseNewApiDefaults() {
        // new-api 对没配缓存倍率的模型按缓存读 1、缓存写 1.25 计（setting/ratio_setting/cache_ratio.go）
        val p = one("newapi", """{"success":true,"data":[{"model_name":"m","quota_type":0,"model_ratio":1,"completion_ratio":1}]}""")
        assertEquals("2", p.cacheRead)
        assertEquals("2.5", p.cacheWrite)
    }

    @Test
    fun absurdValuesAreSkippedNotThrown() {
        val r = UpstreamPricingParser.parse("newapi", """{"success":true,"data":[
            {"model_name":"ok","quota_type":0,"model_ratio":1,"completion_ratio":1},
            {"model_name":"huge","quota_type":0,"model_ratio":1e12,"completion_ratio":1}]}""").getOrThrow()
        assertEquals(listOf("ok"), r.map { it.upstreamModel })
    }
}
