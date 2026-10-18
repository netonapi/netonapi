package logic

import model.Channel
import neton.ai.protocol.diag.DiagnosticCodes
import neton.ai.protocol.diag.LossPolicy

/** 渠道的跨协议损耗策略（V017；docs/neton-ai-protocol-adoption.md §4.2）。 */
object ConversionPolicy {
    val NAMES = setOf("allow", "safe", "strict")

    fun requirePolicy(value: String): String {
        val v = value.trim().lowercase()
        // 管理端入参：拒成字段级 400（require 会被兜底成 500）。
        if (v !in NAMES) rejectField("lossPolicy", GatewayErrorCodes.CHANNEL_LOSS_POLICY_INVALID)
        return v
    }

    /**
     * @param signatureBypass 本次对 Gemini 实际启用了占位签名：`safe` 下把对应诊断列为已授权降级
     *   （框架规格 §5.3）；`strict` 不受影响。
     */
    fun of(channel: Channel, signatureBypass: Boolean = false): LossPolicy = when (channel.lossPolicy) {
        "safe" -> LossPolicy.Safe(if (signatureBypass) setOf(DiagnosticCodes.GEMINI_THOUGHT_SIGNATURE_BYPASSED) else emptySet())
        "strict" -> LossPolicy.Strict
        else -> LossPolicy.Allow
    }
}
