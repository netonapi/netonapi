package logic

import model.Channel
import neton.ai.protocol.diag.DiagnosticCodes
import neton.ai.protocol.diag.LossPolicy

/** 渠道的跨协议损耗策略（V017；docs/neton-ai-protocol-adoption.md §4.2）。 */
object ConversionPolicy {
    val NAMES = setOf("allow", "safe", "strict")

    fun requirePolicy(value: String): String {
        val v = value.trim().lowercase()
        require(v in NAMES) { "lossPolicy must be one of $NAMES" }
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
