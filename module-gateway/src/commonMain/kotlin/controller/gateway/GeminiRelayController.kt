package controller.gateway

import kotlinx.serialization.json.*
import logic.ChannelLogic
import logic.QuotaLogic
import logic.RateLimiter
import logic.SettlementLogic
import logic.RelayEngine
import logic.TokenLogic
import logic.protocol.GeminiAdapter
import neton.core.annotations.Controller
import neton.core.annotations.PathVariable
import neton.core.annotations.Post
import neton.core.http.HttpContext
import neton.core.interfaces.Identity
import neton.logging.Logger
import security.GatewayTokenIdentity

/**
 * Gemini 原生协议面：POST /v1beta/models/{model}:{action}。
 * 模型在 URL 路径、stream 由 action 决定——注入进 body 交统一引擎。
 */
@Controller("/v1beta/models")
class GeminiRelayController(
    private val log: Logger,
    private val channelLogic: ChannelLogic,
    private val tokenLogic: TokenLogic,
    private val quotaLogic: QuotaLogic,
    private val settlementLogic: SettlementLogic,
    private val rateLimiter: RateLimiter,
) {
    private val engine by lazy { RelayEngine(log, channelLogic, tokenLogic, quotaLogic, settlementLogic, rateLimiter) }
    private val json = Json { }

    @Post("/{modelAction}")
    suspend fun generate(@PathVariable modelAction: String, ctx: HttpContext, identity: Identity) {
        val tokenId = (identity as? GatewayTokenIdentity)?.tokenId
        val userId = identity.id.toLongOrNull()
        if (tokenId == null || userId == null) {
            ctx.response.status = neton.core.http.HttpStatus.UNAUTHORIZED
            ctx.response.json(GeminiAdapter.errorBody("invalid_token", "invalid identity"))
            return
        }
        // modelAction 由路由参数绑定，形如 "gemini-1.5-pro:generateContent"
        val colon = modelAction.indexOf(':')
        if (colon < 0) {
            ctx.response.status = neton.core.http.HttpStatus.BAD_REQUEST
            ctx.response.json(GeminiAdapter.errorBody("invalid_request", "expected models/{model}:{action}"))
            return
        }
        val model = modelAction.substring(0, colon)
        val action = modelAction.substring(colon + 1).substringBefore('?')
        val stream = action == "streamGenerateContent"

        val raw = ctx.request.text()
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
            ctx.response.status = neton.core.http.HttpStatus.BAD_REQUEST
            ctx.response.json(GeminiAdapter.errorBody("invalid_request", "request body is not valid JSON"))
            return
        }
        val normalized = buildJsonObject {
            obj.forEach { (k, v) -> put(k, v) }
            put("model", JsonPrimitive(model))
            put("stream", JsonPrimitive(stream))
        }.toString()

        val path = "/v1beta/models/$modelAction"
        engine.relay(ctx, userId, tokenId, GeminiAdapter, path, rawBodyOverride = normalized)
    }
}
