package logic

import model.ChannelModelCost
import model.ModelPrice
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelCostSyncLogicTest {

    private fun old(input: String, output: String = input) = ChannelModelCost(channelId = 1, model = "m", inputPrice = input, outputPrice = output, source = "upstream")
    private fun new(input: String, output: String = input) = ModelPrice(model = "m", inputPrice = input, outputPrice = output)

    @Test fun riseOver50PercentIsPending() = assertEquals(ChannelCostSyncLogic.Decision.PENDING, ChannelCostSyncLogic.decide(old("1"), new("1.6")))
    @Test fun dropOver50PercentIsPending() = assertEquals(ChannelCostSyncLogic.Decision.PENDING, ChannelCostSyncLogic.decide(old("1"), new("0.4")))
    @Test fun riseUnder50PercentApplies() = assertEquals(ChannelCostSyncLogic.Decision.APPLY, ChannelCostSyncLogic.decide(old("1"), new("1.4")))
    @Test fun goingToZeroIsPending() = assertEquals(ChannelCostSyncLogic.Decision.PENDING, ChannelCostSyncLogic.decide(old("1", "2"), new("1", "0")))
    @Test fun firstNonZeroApplies() = assertEquals(ChannelCostSyncLogic.Decision.APPLY, ChannelCostSyncLogic.decide(null, new("1")))
    @Test fun firstAllZeroIsPending() = assertEquals(ChannelCostSyncLogic.Decision.PENDING, ChannelCostSyncLogic.decide(null, new("0")))

    @Test
    fun upstreamNameMapsBackToRequestModels() {
        val ch = model.Channel(id = 1, name = "c", type = "openai_compatible", baseUrl = "http://x", models = "up-c,plain",
            modelMapping = """{"m-a":"up-a","m-b":"up-a"}""")
        assertEquals(setOf("m-a", "m-b"), ChannelCostSyncLogic.requestModelsFor(ch, "up-a"))
        assertEquals(setOf("plain"), ChannelCostSyncLogic.requestModelsFor(ch, "plain"))
        assertEquals(emptySet(), ChannelCostSyncLogic.requestModelsFor(ch, "unknown"))
    }

    @Test
    fun mappedModelIsNotAlsoMatchedByItsOwnName() {
        val ch = model.Channel(id = 1, name = "c", type = "openai_compatible", baseUrl = "http://x", models = "gpt-4o",
            modelMapping = """{"gpt-4o":"gpt-4o-mini"}""")
        assertEquals(emptySet(), ChannelCostSyncLogic.requestModelsFor(ch, "gpt-4o"), "gpt-4o 实际打的是 mini，不能再拿 gpt-4o 的价")
        assertEquals(setOf("gpt-4o"), ChannelCostSyncLogic.requestModelsFor(ch, "gpt-4o-mini"))
    }
}
