package logic

import model.Channel
import model.ChannelModelCost
import model.ModelPrice
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelCostLogicTest {

    private val ch = Channel(id = 7, name = "c", type = "openai_compatible", baseUrl = "http://x", models = "m-a,m-b", costDiscount = "2.0",
        modelMapping = """{"m-a":"up","m-b":"up"}""")
    private val prices = mapOf(
        "m-a" to ModelPrice(model = "m-a", inputPrice = "10", outputPrice = "10"),
        "up" to ModelPrice(model = "up", inputPrice = "1", outputPrice = "1"),
    )
    private fun row(model: String, input: String) = ChannelModelCost(channelId = 7, model = model, inputPrice = input, outputPrice = input, source = "manual")

    @Test
    fun channelRowWinsAndIgnoresDiscount() {
        val b = ChannelCostLogic.costForLoaded(ch, "m-a", "up", prices["m-a"], mapOf((7L to "m-a") to row("m-a", "0.3")), prices)!!
        assertEquals("0.3", b.price.inputPrice)
        assertEquals("1", b.discount)
        assertEquals("channel", b.source)
    }

    @Test
    fun fallsBackToMappedOfficialTimesDiscount() {
        val b = ChannelCostLogic.costForLoaded(ch, "m-a", "up", prices["m-a"], emptyMap(), prices)!!
        assertEquals("1", b.price.inputPrice)
        assertEquals("2.0", b.discount)
        assertEquals("official", b.source)
    }

    @Test
    fun mappedTargetWithoutPriceUsesRequestPrice() {
        val b = ChannelCostLogic.costForLoaded(ch, "m-a", "deployment-1", prices["m-a"], emptyMap(), prices)!!
        assertEquals("10", b.price.inputPrice)
    }

    @Test
    fun manyRequestModelsToOneUpstream() {
        val costs = mapOf((7L to "m-a") to row("m-a", "0.3"), (7L to "m-b") to row("m-b", "0.5"))
        assertEquals("0.3", ChannelCostLogic.costForLoaded(ch, "m-a", "up", prices["m-a"], costs, prices)!!.price.inputPrice)
        assertEquals("0.5", ChannelCostLogic.costForLoaded(ch, "m-b", "up", null, costs, prices)!!.price.inputPrice)
        // 都没有成本行时都回到映射目标的官方价
        assertEquals("1", ChannelCostLogic.costForLoaded(ch, "m-b", "up", null, emptyMap(), prices)!!.price.inputPrice)
    }

    @Test
    fun upstreamRowsIgnoredAfterSwitchingBackToOfficial() {
        val official = ch.copy(costSource = "official")
        val upstreamRow = row("m-a", "0.3").copy(source = "upstream")
        val b = ChannelCostLogic.costForLoaded(official, "m-a", "up", prices["m-a"], mapOf((7L to "m-a") to upstreamRow), prices)!!
        assertEquals("official", b.source, "切回官方价后，旧的上游成本行不能继续生效")
        // 手填的行始终生效
        val manual = ChannelCostLogic.costForLoaded(official, "m-a", "up", prices["m-a"], mapOf((7L to "m-a") to row("m-a", "0.3")), prices)!!
        assertEquals("channel", manual.source)
    }
}
