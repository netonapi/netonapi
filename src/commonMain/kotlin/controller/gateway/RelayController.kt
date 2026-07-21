package controller.gateway

import logic.ChannelLogic
import logic.QuotaLogic
import logic.RelayEngine
import logic.TokenLogic
import neton.core.annotations.Controller
import neton.core.annotations.Post
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
) {
    private val engine by lazy { RelayEngine(log, channelLogic, tokenLogic, quotaLogic) }

    @Post("/chat/completions")
    suspend fun chatCompletions(ctx: HttpContext, identity: Identity) =
        relay(ctx, identity, "/v1/chat/completions")

    @Post("/embeddings")
    suspend fun embeddings(ctx: HttpContext, identity: Identity) =
        relay(ctx, identity, "/v1/embeddings")

    private suspend fun relay(ctx: HttpContext, identity: Identity, path: String) {
        val tokenId = (identity as? GatewayTokenIdentity)?.tokenId
        val userId = identity.id.toLongOrNull()
        if (tokenId == null || userId == null) {
            ctx.response.status = neton.core.http.HttpStatus.UNAUTHORIZED
            ctx.response.json("""{"error":{"message":"invalid identity","type":"invalid_token","code":"invalid_token"}}""")
            return
        }
        engine.relay(ctx, userId, tokenId, path)
    }
}
