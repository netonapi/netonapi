package logic

import model.Channel
import neton.core.http.ValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 上游成本设置必须由管理员明确给出，不替他补默认值（上游几乎不可能按官方价 1:1 计费）。 */
class ChannelCostConfigTest {

    private val base = Channel(name = "c", type = "openai_compatible", baseUrl = "https://u.example.com/v1")
    private fun input(source: String? = null, discount: String? = null, format: String? = null, group: String? = null, credit: String? = null) =
        ChannelCostLogic.CostInput(source, discount, format, null, group, credit)

    private fun rejected(block: () -> Unit): String = assertFailsWith<ValidationException> { block() }.message ?: ""

    @Test fun createRequiresCostSourceAndDiscount() {
        assertEquals(GatewayErrorCodes.CHANNEL_COST_SOURCE_REQUIRED, rejected { ChannelCostLogic.resolveCostConfig(input(discount = "0.6"), base, null) })
        assertEquals(GatewayErrorCodes.CHANNEL_COST_DISCOUNT_REQUIRED, rejected { ChannelCostLogic.resolveCostConfig(input(source = "official"), base, null) })
        assertEquals(GatewayErrorCodes.CHANNEL_COST_DISCOUNT_REQUIRED, rejected { ChannelCostLogic.resolveCostConfig(input(source = "official", discount = "  "), base, null) })
    }

    @Test fun createWithOfficialDiscountKeepsTheGivenDiscount() {
        val ch = ChannelCostLogic.resolveCostConfig(input(source = "official", discount = "0.6"), base, null)
        assertEquals("official", ch.costSource)
        assertEquals("0.6", ch.costDiscount)
    }

    @Test fun upstreamSourceRequiresFormatGroupRatioAndCreditRateInThatOrder() {
        assertEquals(GatewayErrorCodes.CHANNEL_PRICING_FORMAT_REQUIRED,
            rejected { ChannelCostLogic.resolveCostConfig(input(source = "upstream", discount = "1"), base, null) })
        assertEquals(GatewayErrorCodes.CHANNEL_UPSTREAM_GROUP_RATIO_REQUIRED,
            rejected { ChannelCostLogic.resolveCostConfig(input(source = "upstream", discount = "1", format = "newapi"), base, null) })
        assertEquals(GatewayErrorCodes.CHANNEL_UPSTREAM_CREDIT_RATE_REQUIRED,
            rejected { ChannelCostLogic.resolveCostConfig(input(source = "upstream", discount = "1", format = "newapi", group = "0.1"), base, null) })
        val ch = ChannelCostLogic.resolveCostConfig(input(source = "upstream", discount = "1", format = "newapi", group = "0.1", credit = "0.139"), base, null)
        assertEquals("0.1", ch.upstreamGroupRatio)
        assertEquals("0.139", ch.upstreamCreditRate)
    }

    @Test fun updateKeepsWhatIsNotSent() {
        val current = base.copy(id = 9, costSource = "upstream", costDiscount = "0.7", upstreamPricingFormat = "newapi", upstreamGroupRatio = "0.1", upstreamCreditRate = "0.139")
        val ch = ChannelCostLogic.resolveCostConfig(input(), base, current)
        assertEquals("upstream", ch.costSource)
        assertEquals("0.7", ch.costDiscount)
        assertEquals("0.1", ch.upstreamGroupRatio)
        assertEquals("0.139", ch.upstreamCreditRate)
    }

    @Test fun switchingToUpstreamNeedsExplicitRatios() {
        // 原来不是上游价目接口：库里的分组倍率 / 汇率 1 只是占位，不能当成管理员填过的值
        val current = base.copy(id = 9, costSource = "official", costDiscount = "0.7")
        assertEquals(GatewayErrorCodes.CHANNEL_UPSTREAM_GROUP_RATIO_REQUIRED,
            rejected { ChannelCostLogic.resolveCostConfig(input(source = "upstream", format = "newapi"), base, current) })
    }
}
