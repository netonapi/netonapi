package controller.gateway

import logic.ChannelLogic
import logic.RelayRouter
import logic.TokenLogic
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.http.HttpResponse
import neton.core.interfaces.Identity
import security.GatewayTokenIdentity

/**
 * OpenAI 兼容 `/v1/models`。gateway 组裸响应（不走 envelope）。
 *
 * 返回**该令牌此刻真正可用**的模型：启用且未冷却的渠道 ∩ 令牌分组 ∩ 令牌模型白名单。
 * 客户端（ChatBox / Cherry Studio 等）靠这个接口列模型，返回空表等于「接进来不可用」。
 */
@Controller("/v1/models")
class ModelsController(
    private val channelLogic: ChannelLogic,
    private val tokenLogic: TokenLogic,
) {

    @Get("")
    suspend fun list(response: HttpResponse, identity: Identity) {
        val created = nowMillis() / 1000
        val data = availableModels(identity).joinToString(",") { m ->
            """{"id":${quote(m)},"object":"model","created":$created,"owned_by":"newgate"}"""
        }
        response.json("""{"object":"list","data":[$data]}""")
    }

    private suspend fun availableModels(identity: Identity): List<String> {
        val tokenId = (identity as? GatewayTokenIdentity)?.tokenId ?: return emptyList()
        val token = tokenLogic.get(tokenId) ?: return emptyList()
        val group = token.groupOverride?.takeIf { it.isNotBlank() } ?: "default"
        val allowed = token.allowedModels.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        val enabled = channelLogic.listEnabled()
        val now = nowMillis()
        return enabled
            .asSequence()
            .flatMap { ch -> ch.models.split(',').map { it.trim() }.filter { it.isNotEmpty() }.asSequence() }
            .distinct()
            // 逐个复核可路由性：分组交集、冷却、状态一律交给 RelayRouter 判定，避免与实际路由漂移
            .filter { model -> RelayRouter.candidates(enabled, model, group, now).isNotEmpty() }
            .filter { allowed.isEmpty() || it in allowed }
            .sorted()
            .toList()
    }

    /** 模型名进 JSON 前转义（模型名由管理员填写，不能假定安全）。 */
    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
        append('"')
    }

    private fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}
