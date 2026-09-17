package controller.gateway

import logic.ChannelLogic
import logic.QuotaLogic
import logic.RateLimiter
import logic.SettlementLogic
import logic.RelayEngine
import logic.TokenLogic
import neton.core.annotations.Controller
import neton.core.annotations.Post
import logic.protocol.OpenAiAdapter
import logic.protocol.AnthropicAdapter
import logic.protocol.ProtocolAdapter
import neton.core.http.HttpContext
import neton.core.interfaces.Identity
import neton.logging.Logger
import security.GatewayTokenIdentity

/** OpenAI 兼容协议面（gateway 组，TokenGuard 已认证）。裸响应，不走 envelope。 */
@Controller("/v1")
class RelayController(
    private val log: Logger,
    private val channelLogic: ChannelLogic,
    private val tokenLogic: TokenLogic,
    private val quotaLogic: QuotaLogic,
    private val settlementLogic: SettlementLogic,
    private val rateLimiter: RateLimiter,
) {
    private val engine by lazy { RelayEngine(log, channelLogic, tokenLogic, quotaLogic, settlementLogic, rateLimiter) }

    @Post("/chat/completions")
    suspend fun chatCompletions(ctx: HttpContext, identity: Identity) =
        relay(ctx, identity, OpenAiAdapter, "/v1/chat/completions")

    /** OpenAI Responses API：同协议透传到 OpenAI 兼容 / Azure 上游；其它类型渠道不参与路由。 */
    @Post("/responses")
    suspend fun responses(ctx: HttpContext, identity: Identity) =
        relay(ctx, identity, OpenAiAdapter, "/v1/responses")

    /** 图片生成：同协议透传到 OpenAI 兼容 / Azure 上游；渠道要声明 images 能力。 */
    @Post("/images/generations")
    suspend fun images(ctx: HttpContext, identity: Identity) =
        relay(ctx, identity, OpenAiAdapter, "/v1/images/generations")

    /** 重排序：同协议透传到 OpenAI 兼容上游；渠道要声明 rerank 能力。 */
    @Post("/rerank")
    suspend fun rerank(ctx: HttpContext, identity: Identity) =
        relay(ctx, identity, OpenAiAdapter, "/v1/rerank")

    @Post("/embeddings")
    suspend fun embeddings(ctx: HttpContext, identity: Identity) =
        relay(ctx, identity, OpenAiAdapter, "/v1/embeddings")

    @Post("/messages")
    suspend fun messages(ctx: HttpContext, identity: Identity) =
        relay(ctx, identity, AnthropicAdapter, "/v1/messages")

    private suspend fun relay(ctx: HttpContext, identity: Identity, inbound: ProtocolAdapter, path: String) {
        val tokenId = (identity as? GatewayTokenIdentity)?.tokenId
        val userId = identity.id.toLongOrNull()
        if (tokenId == null || userId == null) {
            ctx.response.status = neton.core.http.HttpStatus.UNAUTHORIZED
            ctx.response.json("""{"error":{"message":"invalid identity","type":"invalid_token","code":"invalid_token"}}""")
            return
        }
        engine.relay(ctx, userId, tokenId, inbound, path)
    }
}
