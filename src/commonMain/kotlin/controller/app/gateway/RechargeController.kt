package controller.app.gateway

import kotlinx.serialization.Serializable
import logic.QuotaLogic
import neton.core.annotations.*
import neton.core.http.ValidationError
import neton.core.http.ValidationException
import neton.core.interfaces.Identity
import neton.logging.Logger
import neton.security.random.SecureRandom

@Serializable
data class MockRechargeRequest(
    /** 充值额度 μUSD */
    val amount: Long,
)

@Serializable
data class RechargeResultVO(val balance: Long, val ref: String)

/**
 * 额度充值。
 *
 * 目前只有**模拟充值**，用于打通「付款 → 额度到账 → 能用」这条链路的联调；
 * 它凭空发放额度，因此默认关闭，必须显式设置 `NEWGATE_ENABLE_MOCK_RECHARGE=true` 才可用，
 * 否则一律拒绝——绝不能在生产环境被误开成免费领额度的接口。
 *
 * 真实充值（payment 下单 → 支付回调 → 按订单号 grant）待接入：钱包是法币账本、
 * 网关额度是 μUSD 账本，两者通过订单号关联而非共用余额。
 */
@Controller("/gateway/recharge")
class RechargeController(
    private val log: Logger,
    private val quotaLogic: QuotaLogic,
) {

    @Post("/mock")
    suspend fun mock(identity: Identity, @Body request: MockRechargeRequest): RechargeResultVO {
        if (!mockEnabled()) {
            throw ValidationException(listOf(ValidationError(
                path = "mock",
                message = "mock recharge is disabled; set NEWGATE_ENABLE_MOCK_RECHARGE=true to enable it in a test environment",
            )))
        }
        if (request.amount <= 0) {
            throw ValidationException(listOf(ValidationError(path = "amount", message = "must be positive")))
        }
        val userId = identity.id.toLong()
        // ref 唯一 → 重复提交不会重复入账（台账 ref 上有唯一约束）
        val ref = "mock:" + SecureRandom.hex(12)
        val balance = quotaLogic.grant(userId, request.amount, type = "grant", ref = ref)
        log.warn("MOCK recharge granted userId=$userId amount=${request.amount} ref=$ref（仅测试环境）")
        return RechargeResultVO(balance, ref)
    }

    private fun mockEnabled(): Boolean =
        neton.core.config.getEnv("NEWGATE_ENABLE_MOCK_RECHARGE")?.lowercase() == "true"
}
