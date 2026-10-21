package logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PricingCheckHintsTest {

    private fun route(id: Long, priority: Int, costInput: String, status: Int = 1, cooldownUntil: Long = 0, source: String = "channel",
                      configured: String = "manual", discount: String = "1", stale: Boolean = false) =
        ChannelRouteVO(channelId = id, name = "c$id", priority = priority, weight = 1, status = status, cooldownUntil = cooldownUntil,
            lastProbeOk = 1, costInput = costInput, costOutput = costInput, costSource = source, configuredSource = configured,
            discount = discount, stale = stale)

    @Test
    fun higherPriorityButMoreExpensiveIsFlagged() {
        val hints = PricingCheckLogic.hintsFor(listOf(route(1, 10, "5"), route(2, 5, "1")), now = 100)
        assertTrue(PricingCheckLogic.HINT_PRIORITY_COST_INVERSION in hints)
    }

    @Test
    fun cheaperLowerPriorityThatIsCoolingDownIsNotFlagged() {
        val hints = PricingCheckLogic.hintsFor(listOf(route(1, 10, "5"), route(2, 5, "1", cooldownUntil = 200)), now = 100)
        assertFalse(PricingCheckLogic.HINT_PRIORITY_COST_INVERSION in hints)
    }

    @Test
    fun onlyOfficialChannelsAtFullPrice() {
        val hints = PricingCheckLogic.hintsFor(listOf(route(1, 1, "3", source = "official", configured = "official", discount = "1.0")), now = 0)
        assertEquals(listOf(PricingCheckLogic.HINT_OFFICIAL_ONLY), hints)
    }

    @Test
    fun upstreamChannelWithoutQuoteAndStaleRows() {
        val hints = PricingCheckLogic.hintsFor(listOf(
            route(1, 1, "3", source = "official", configured = "upstream", discount = "0.5"),
            route(2, 1, "3", stale = true),
        ), now = 0)
        assertTrue(PricingCheckLogic.HINT_UPSTREAM_NOT_PRICED in hints)
        assertTrue(PricingCheckLogic.HINT_COST_STALE in hints)
    }

    @Test
    fun failingUpstreamSyncIsFlagged() {
        val r = route(1, 1, "3").copy(syncFailing = true)
        assertTrue(PricingCheckLogic.HINT_COST_STALE in PricingCheckLogic.hintsFor(listOf(r), now = 0))
    }
}
