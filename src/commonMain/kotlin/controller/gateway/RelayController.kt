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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    /**
     * 文本转语音：JSON 进、音频字节出。上游不给 usage，按 `input` 的字符数计输入 token
     * （OpenAI 的 TTS 就是按字符收费，定价表里把「输入价」填成每百万字符的价即可）。
     */
    @Post("/audio/speech")
    suspend fun speech(ctx: HttpContext, identity: Identity) {
        val body = ctx.request.text()
        val chars = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["input"]?.jsonPrimitive?.contentOrNull?.length
        }.getOrNull() ?: 0
        relay(ctx, identity, OpenAiAdapter, "/v1/audio/speech", rawBody = body,
            extras = RelayEngine.RelayExtras(binaryResponse = true, requestUsage = logic.Usage(promptTokens = chars)))
    }

    /** 语音转文字 / 翻译：multipart 原样透传（只改写 model 字段），响应按上游的内容类型回。 */
    @Post("/audio/transcriptions")
    suspend fun transcriptions(ctx: HttpContext, identity: Identity) = relayMultipart(ctx, identity, "/v1/audio/transcriptions")

    @Post("/audio/translations")
    suspend fun translations(ctx: HttpContext, identity: Identity) = relayMultipart(ctx, identity, "/v1/audio/translations")

    private suspend fun relayMultipart(ctx: HttpContext, identity: Identity, path: String) {
        val contentType = ctx.request.contentType
        val boundary = logic.protocol.MultipartForm.boundary(contentType)
        if (boundary == null) {
            ctx.response.status = neton.core.http.HttpStatus.BAD_REQUEST
            ctx.response.json(OpenAiAdapter.errorBody("invalid_request_error", "multipart/form-data with a boundary is required"))
            return
        }
        val bytes = ctx.request.body()
        val model = logic.protocol.MultipartForm.field(bytes, boundary, "model")?.trim() ?: ""
        // 路由与计费只需要 model；真正发给上游的是原始字节
        val synthetic = """{"model":${kotlinx.serialization.json.JsonPrimitive(model)}}"""
        relay(ctx, identity, OpenAiAdapter, path, rawBody = synthetic,
            extras = RelayEngine.RelayExtras(bodyBytes = bytes, contentType = contentType))
    }

    @Post("/embeddings")
    suspend fun embeddings(ctx: HttpContext, identity: Identity) =
        relay(ctx, identity, OpenAiAdapter, "/v1/embeddings")

    @Post("/messages")
    suspend fun messages(ctx: HttpContext, identity: Identity) =
        relay(ctx, identity, AnthropicAdapter, "/v1/messages")

    private suspend fun relay(
        ctx: HttpContext, identity: Identity, inbound: ProtocolAdapter, path: String,
        rawBody: String? = null, extras: RelayEngine.RelayExtras = RelayEngine.RelayExtras(),
    ) {
        val tokenId = (identity as? GatewayTokenIdentity)?.tokenId
        val userId = identity.id.toLongOrNull()
        if (tokenId == null || userId == null) {
            ctx.response.status = neton.core.http.HttpStatus.UNAUTHORIZED
            ctx.response.json("""{"error":{"message":"invalid identity","type":"invalid_token","code":"invalid_token"}}""")
            return
        }
        engine.relay(ctx, userId, tokenId, inbound, path, rawBody, extras)
    }
}
