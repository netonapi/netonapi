import contract.testing.ErrorMessageGuard
import kotlin.test.Test

/**
 * 业务错误 message 必须是 [logic.GatewayErrorCodes] 机器码（规范 neton-docs spec/application/gateway-errors.md）。
 *
 * OpenAI / Anthropic / Gemini 兼容转发端点返回的厂商格式错误体（`{"error":{"message":…,"type":…}}`）
 * 是协议契约，不经 HttpException，守卫本来就扫不到，也不该管。
 */
class ErrorMessageGuardTest {
    @Test
    fun businessErrorMessagesAreMachineCodes() {
        ErrorMessageGuard.assertModuleClean(
            allowlist = setOf(
                // rejectField 本身：code 形参透传；调用点由下面的 constructors 条目逐个检查。
                "kotlin/logic/GatewayErrorCodes.kt#ValidationException#code",
                "kotlin/logic/GatewayErrorCodes.kt#ValidationError#code",
                // NetGuard.UrlVerdict.Rejected.code 恒为 GatewayErrorCodes.UPSTREAM_* 常量（NetGuard 内构造）。
                "kotlin/controller/admin/channel/ChannelController.kt#rejectField#v.code",
            ),
            // 模块的字段级拒绝 helper：第 2 个实参（code）必须是目录常量。
            constructors = ErrorMessageGuard.DEFAULT_CONSTRUCTORS + mapOf("rejectField" to listOf(1)),
        )
    }
}
