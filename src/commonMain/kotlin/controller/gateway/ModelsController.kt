package controller.gateway

import logic.BillingGroupResolver
import logic.ChannelLogic
import logic.GroupResolution
import logic.RelayRouter
import logic.TokenLogic
import model.GatewayToken
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.http.HttpResponse
import neton.core.http.HttpStatus
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
        val tokenId = (identity as? GatewayTokenIdentity)?.tokenId
        val token = tokenId?.let { tokenLogic.get(it) }
        if (token == null) {
            response.json("""{"object":"list","data":[]}""")
            return
        }
        // 与 RelayEngine 走同一个解析器：广场列出来的模型必须就是此刻真能路由的模型。
        // 拒绝时**不回空表** —— 空表在客户端看来等于「这个 key 没有任何可用模型」，会把人引去查渠道，
        // 而真实原因是计费组配置坏了或该会员组没开通，两者处置方完全不同。
        val groupCode = when (val r = BillingGroupResolver.resolve(token.userId, token.groupOverride)) {
            is GroupResolution.Rejected -> {
                response.status = HttpStatus.fromCode(r.reason.httpStatus) ?: HttpStatus.BAD_GATEWAY
                response.json(
                    """{"error":{"message":${quote(r.message)},"type":${quote(r.reason.code)}}}"""
                )
                return
            }
            is GroupResolution.Resolved -> r.group.code
        }
        val data = availableModels(token, groupCode).joinToString(",") { m ->
            """{"id":${quote(m)},"object":"model","created":$created,"owned_by":"nanogate"}"""
        }
        response.json("""{"object":"list","data":[$data]}""")
    }

    private suspend fun availableModels(token: GatewayToken, group: String): List<String> {
        val allowed = token.allowedModels.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        val enabled = channelLogic.listEnabled()
        val now = nowMillis()
        return enabled
            .asSequence()
            .flatMap { ch -> ch.models.split(',').map { it.trim() }.filter { it.isNotEmpty() }.asSequence() }
            .distinct()
            // 逐个复核可路由性：分组交集、冷却、状态、能力一律交给 RelayRouter 判定，避免与实际路由漂移。
            // 默认 chat 能力：客户端拿这张表去发对话请求，只声明 embeddings 的模型列进来会被当成 chat 调。
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
