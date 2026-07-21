package logic

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import model.Channel
import model.GatewayGroup
import model.GatewayToken
import model.ModelPrice
import model.UsageLog
import neton.core.http.HttpContext
import neton.core.http.HttpStatus
import neton.http.client.*
import neton.logging.Logger
import table.GatewayGroupTable
import table.GatewayTokenTable
import table.ModelPriceTable
import table.UsageLogTable
import logic.protocol.ProtocolAdapter
import logic.protocol.OpenAiAdapter
import logic.protocol.AnthropicAdapter
import neton.database.dsl.*
import kotlin.random.Random

/**
 * 中转引擎（M1b）：同协议透传改写 → 渠道路由重试 → 直通/计费/日志。
 * 每请求由 RelayController 构造（controller 每请求实例化），渠道 HTTP client 缓存在 companion。
 */
class RelayEngine(
    private val log: Logger,
    private val channelLogic: ChannelLogic,
    private val tokenLogic: TokenLogic,
    private val quotaLogic: QuotaLogic,
) {

    suspend fun relay(ctx: HttpContext, userId: Long, tokenId: Long, inbound: ProtocolAdapter, path: String) {
        val startMs = now()
        val rawBody = ctx.request.text()

        val token = tokenLogic.get(tokenId)
        if (token == null || token.status != 1) return respondError(ctx, inbound, 401, "invalid_token", "token not found or disabled")

        // 预检：令牌预算 + 账户余额
        token.quotaBudget?.let { budget ->
            if (token.quotaUsed >= budget) return respondError(ctx, inbound, 429, "insufficient_quota", "token budget exhausted")
        }
        if (quotaLogic.account(userId).balance <= 0) {
            return respondError(ctx, inbound, 429, "insufficient_quota", "account balance exhausted")
        }

        val group = token.groupOverride?.takeIf { it.isNotBlank() } ?: "default"
        val requestModel = runCatching { RequestRewriter.rewrite(rawBody, DUMMY).requestModel }.getOrElse {
            return respondError(ctx, inbound, 400, "invalid_request_error", "request body is not valid JSON")
        }
        if (requestModel.isEmpty()) return respondError(ctx, inbound, 400, "invalid_request_error", "model is required")

        // 令牌模型白名单
        if (token.allowedModels.isNotBlank() &&
            !token.allowedModels.split(',').map { it.trim() }.contains(requestModel)
        ) return respondError(ctx, inbound, 403, "model_not_allowed", "model '$requestModel' not allowed for this token")

        val candidates = RelayRouter.candidates(channelLogic.listEnabled(), requestModel, group)
        if (candidates.isEmpty()) return respondError(ctx, inbound, 503, "no_available_channel", "no channel available for model '$requestModel'")

        val tried = mutableSetOf<Long>()
        var lastError: String? = null
        val maxAttempts = minOf(3, candidates.size)

        repeat(maxAttempts) {
            val channel = RelayRouter.pick(candidates, tried, Random) ?: return@repeat
            tried += channel.id
            val upstream = adapterForChannel(channel.type)
            if (upstream == null) {
                lastError = "unknown channel type ${channel.type}"
                return@repeat
            }
            val transform = buildTransform(inbound, upstream, rawBody, channel)
            if (transform == null) {
                lastError = "cross-protocol relay (inbound=${inbound.id} → channel.type=${channel.type}) not yet supported"
                return@repeat
            }
            val key = channelLogic.pickKey(channel.id)
            if (key == null) { lastError = "channel ${channel.id} has no available key"; return@repeat }

            val rewritten = transform.rewritten
            val outcome = try {
                execute(ctx, channel, key.apiKey, transform, path, token, group, startMs)
            } catch (e: NetonHttpException) {
                val err = e.error
                val retryable = when (err) {
                    is NetonHttpError.Http -> err.statusCode == 429 || err.statusCode >= 500
                    is NetonHttpError.Network, is NetonHttpError.Timeout -> true
                    else -> false
                }
                if (!retryable && err is NetonHttpError.Http) {
                    // 非重试 4xx：语义透传（body 原样，擦除上游 headers）
                    recordLog(token, channel, rewritten, Usage(), group, startMs, status = "error", errorCode = "upstream_${err.statusCode}", ttfbMs = null)
                    respondRaw(ctx, err.statusCode, err.body ?: inbound.errorBody("upstream_error", "upstream error ${err.statusCode}"))
                    return
                }
                lastError = err.toString().replace("\n", " ").take(160)
                log.warn("relay retry channel=${channel.id} err=$lastError")
                null
            }
            if (outcome != null) return  // 成功（计费与日志已在 execute 内完成）
        }
        respondError(ctx, inbound, 503, "no_available_channel", lastError ?: "all channels failed")
    }

    private fun adapterForChannel(type: String): ProtocolAdapter? = when (type) {
        "openai_compatible", "azure_openai" -> OpenAiAdapter
        "anthropic" -> AnthropicAdapter
        else -> null
    }

    /**
     * 组装转发变换：同协议 = 恒等透传；跨协议 = codec 转换（spec 决策 A，产品侧直译）。
     * 返回 null 表示该 (inbound × 渠道) 组合暂不支持。
     */
    private fun buildTransform(
        inbound: ProtocolAdapter, upstream: ProtocolAdapter, rawBody: String, channel: Channel,
    ): RelayTransform? {
        // 同协议：模型映射 + 协议原生改写，响应恒等透传
        if (inbound.id == upstream.id) {
            val rw = upstream.rewrite(rawBody, channel)
            return RelayTransform(
                rewritten = rw, upstream = upstream,
                transformResponseBody = { it },
                streamTransformerFactory = null,
            )
        }
        // 跨协议：Anthropic 入站 → OpenAI 上游（Slice 2）
        if (inbound.id == "anthropic" && upstream.id == "openai") {
            // 先在 Anthropic body 上映射 model（不注入 OpenAI 专属 stream_options）
            val mapped = RequestRewriter.rewrite(rawBody, channel, injectStreamOptions = false)
            val openaiBody = logic.protocol.codec.AnthropicToOpenAiRequest.convert(mapped.body)
            return RelayTransform(
                rewritten = Rewritten(openaiBody, mapped.requestModel, mapped.upstreamModel, mapped.stream),
                upstream = upstream,
                transformResponseBody = { logic.protocol.codec.OpenAiToAnthropicResponse.convert(it) },
                streamTransformerFactory = {
                    val sm = logic.protocol.codec.OpenAiToAnthropicStream(model = mapped.requestModel)
                    object : StreamTransformer {
                        override fun feed(chunk: String) = sm.feed(chunk)
                        override fun finish() = sm.finish()
                    }
                },
            )
        }
        return null
    }

    /** 成功返回非 null；可重试失败抛 NetonHttpException。 */
    private suspend fun execute(
        ctx: HttpContext,
        channel: Channel,
        apiKey: String,
        transform: RelayTransform,
        path: String,
        token: GatewayToken,
        group: String,
        startMs: Long,
    ): Unit? {
        val rewritten = transform.rewritten
        val upstream = transform.upstream
        val client = clientFor(channel)
        val request = NetonHttpRequest(
            method = NetonHttpMethod.Post,
            url = channel.baseUrl.trimEnd('/') + upstream.upstreamPath(path),
            headers = upstream.upstreamHeaders(apiKey),
            body = NetonHttpBody.Json(rewritten.body),
        )

        if (!rewritten.stream) {
            val res = client.request(request)
            if (res.statusCode == 429 || res.statusCode >= 500) {
                throw NetonHttpException(NetonHttpError.Http(res.statusCode, "HTTP ${res.statusCode}", res.body))
            }
            if (res.statusCode !in 200..299) {
                recordLog(token, channel, rewritten, Usage(), group, startMs, "error", "upstream_${res.statusCode}", null)
                respondRaw(ctx, res.statusCode, res.body)  // 上游错误 body 原样（擦除 headers）
                return Unit
            }
            val ttfb = now() - startMs
            val usage = upstream.extractUsage(res.body)          // usage 从上游原生响应提取
            respondRaw(ctx, 200, transform.transformResponseBody(res.body))  // body 按需转协议
            bill(token, channel, rewritten, usage, group, startMs, ttfb)
            return Unit
        }

        // 流式：首字节前的失败由 stream() 抛 Http 异常（可重试窗口）；开流后直通不回头
        val accumulator = upstream.streamUsageAccumulator()   // usage 累加喂上游原生块
        val streamTransformer = transform.streamTransformerFactory?.invoke()
        var ttfbMs: Long? = null
        ctx.response.status = HttpStatus.OK
        ctx.response.contentType = "text/event-stream; charset=utf-8"
        ctx.response.header("Cache-Control", "no-cache")
        ctx.response.header("X-Accel-Buffering", "no")
        try {
            ctx.response.stream {
                client.stream(request).collect { chunk ->
                    val text = when (chunk) {
                        is NetonHttpStreamChunk.Bytes -> chunk.bytes.decodeToString()
                        is NetonHttpStreamChunk.Text -> chunk.text
                        is NetonHttpStreamChunk.End -> return@collect
                    }
                    if (ttfbMs == null) ttfbMs = now() - startMs
                    accumulator.feed(text)  // 始终喂上游原文用于计费
                    if (streamTransformer != null) {
                        val converted = streamTransformer.feed(text)
                        if (converted.isNotEmpty()) writeChunk(converted)
                    } else {
                        writeChunk(text)  // 同协议直通
                    }
                }
                // 跨协议：补收尾事件（message_delta/message_stop）
                streamTransformer?.finish()?.let { if (it.isNotEmpty()) writeChunk(it) }
            }
        } catch (e: NetonHttpException) {
            if (ttfbMs == null) throw e  // 首字节前：交给重试
            log.warn("relay stream aborted mid-flight channel=${channel.id}: ${e.error}")
            bill(token, channel, rewritten, accumulator.usage(), group, startMs, ttfbMs, status = "partial")
            return Unit
        }
        bill(token, channel, rewritten, accumulator.usage(), group, startMs, ttfbMs)
        return Unit
    }

    /** 转发变换：请求体已终态；响应体/流按需转协议（同协议为恒等）。 */
    class RelayTransform(
        val rewritten: Rewritten,
        val upstream: ProtocolAdapter,
        val transformResponseBody: (String) -> String,
        val streamTransformerFactory: (() -> StreamTransformer)?,
    )

    interface StreamTransformer {
        fun feed(chunk: String): String
        fun finish(): String
    }

    private suspend fun bill(
        token: GatewayToken,
        channel: Channel,
        rewritten: Rewritten,
        usage: Usage?,
        group: String,
        startMs: Long,
        ttfbMs: Long?,
        status: String = if (usage != null) "ok" else "partial",
    ) {
        val price: ModelPrice? = ModelPriceTable.oneWhere { ModelPrice::model eq rewritten.requestModel }
        val ratio = GatewayGroupTable.oneWhere { GatewayGroup::name eq group }?.ratio ?: "1.0"
        val effectiveUsage = usage ?: Usage()
        val charged = if (price != null) PricingCalc.charge(effectiveUsage, price, ratio) else 0L
        val cost = if (price != null) PricingCalc.cost(effectiveUsage, price, channel.costDiscount) else 0L
        if (price == null) log.warn("relay unpriced model=${rewritten.requestModel}（放行计零，请配置定价）")

        val logId = recordLog(token, channel, rewritten, effectiveUsage, group, startMs, status, null, ttfbMs, charged, cost)
        if (charged > 0) {
            quotaLogic.consume(token.userId, charged, ref = "usage:$logId")
            val current = GatewayTokenTable.get(token.id)
            if (current != null) GatewayTokenTable.update(current.copy(quotaUsed = current.quotaUsed + charged))
        }
    }

    private suspend fun recordLog(
        token: GatewayToken, channel: Channel, rewritten: Rewritten, usage: Usage,
        group: String, startMs: Long, status: String, errorCode: String?, ttfbMs: Long?,
        charged: Long = 0, cost: Long = 0,
    ): Long = UsageLogTable.insert(
        UsageLog(
            userId = token.userId, tokenId = token.id, channelId = channel.id,
            requestModel = rewritten.requestModel, upstreamModel = rewritten.upstreamModel,
            promptTokens = usage.promptTokens, completionTokens = usage.completionTokens,
            cacheReadTokens = usage.cacheReadTokens, cacheWriteTokens = usage.cacheWriteTokens,
            charged = charged, cost = cost,
            ttfbMs = ttfbMs, latencyMs = now() - startMs,
            stream = if (rewritten.stream) 1 else 0,
            status = status, errorCode = errorCode,
        )
    ).id

    private suspend fun respondError(ctx: HttpContext, inbound: ProtocolAdapter, status: Int, code: String, message: String) {
        respondRaw(ctx, status, inbound.errorBody(code, message))
    }

    private suspend fun respondRaw(ctx: HttpContext, status: Int, body: String) {
        ctx.response.status = HttpStatus.fromCode(status) ?: HttpStatus.BAD_GATEWAY
        ctx.response.json(body)
    }

    private fun now(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    companion object {
        /** JSON 解析用的哑渠道（rewrite 首过只取 model/stream）。 */
        private val DUMMY = Channel(id = 0, name = "", type = "", baseUrl = "")

        private val clientMutex = Mutex()
        private val clients = mutableMapOf<String, NetonHttpClient>()

        /** 渠道 client 缓存：key 含影响连接行为的字段；渠道配置变更后 key 变化自然重建。 */
        suspend fun clientFor(channel: Channel): NetonHttpClient {
            val key = "${channel.id}|${channel.proxyUrl ?: ""}|${channel.ttfbTimeoutMs}|${channel.idleTimeoutMs}"
            clientMutex.withLock {
                return clients.getOrPut(key) {
                    NetonHttpClient.create {
                        connectMillis = 5_000
                        requestMillis = 600_000               // 长流总上限 10 分钟
                        socketMillis = channel.idleTimeoutMs  // 块间空闲超时
                        proxyUrl = channel.proxyUrl
                    }
                }
            }
        }
    }
}
