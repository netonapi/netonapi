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

    suspend fun relay(ctx: HttpContext, userId: Long, tokenId: Long, path: String) {
        val startMs = now()
        val rawBody = ctx.request.text()

        val token = tokenLogic.get(tokenId)
        if (token == null || token.status != 1) return respondError(ctx, 401, "invalid_token", "token not found or disabled")

        // 预检：令牌预算 + 账户余额
        token.quotaBudget?.let { budget ->
            if (token.quotaUsed >= budget) return respondError(ctx, 429, "insufficient_quota", "token budget exhausted")
        }
        if (quotaLogic.account(userId).balance <= 0) {
            return respondError(ctx, 429, "insufficient_quota", "account balance exhausted")
        }

        val group = token.groupOverride?.takeIf { it.isNotBlank() } ?: "default"
        val requestModel = runCatching { RequestRewriter.rewrite(rawBody, DUMMY).requestModel }.getOrElse {
            return respondError(ctx, 400, "invalid_request_error", "request body is not valid JSON")
        }
        if (requestModel.isEmpty()) return respondError(ctx, 400, "invalid_request_error", "model is required")

        // 令牌模型白名单
        if (token.allowedModels.isNotBlank() &&
            !token.allowedModels.split(',').map { it.trim() }.contains(requestModel)
        ) return respondError(ctx, 403, "model_not_allowed", "model '$requestModel' not allowed for this token")

        val candidates = RelayRouter.candidates(channelLogic.listEnabled(), requestModel, group)
        if (candidates.isEmpty()) return respondError(ctx, 503, "no_available_channel", "no channel available for model '$requestModel'")

        val tried = mutableSetOf<Long>()
        var lastError: String? = null
        val maxAttempts = minOf(3, candidates.size)

        repeat(maxAttempts) {
            val channel = RelayRouter.pick(candidates, tried, Random) ?: return@repeat
            tried += channel.id
            val key = channelLogic.pickKey(channel.id)
            if (key == null) { lastError = "channel ${channel.id} has no available key"; return@repeat }

            val rewritten = RequestRewriter.rewrite(rawBody, channel)
            val outcome = try {
                execute(ctx, channel, key.apiKey, rewritten, path, token, group, startMs)
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
                    respondRaw(ctx, err.statusCode, err.body ?: """{"error":{"message":"upstream error","type":"upstream_error","code":${err.statusCode}}}""")
                    return
                }
                lastError = err.toString().take(200)
                log.warn("relay retry channel=${channel.id} err=$lastError")
                null
            }
            if (outcome != null) return  // 成功（计费与日志已在 execute 内完成）
        }
        respondError(ctx, 503, "no_available_channel", lastError ?: "all channels failed")
    }

    /** 成功返回非 null；可重试失败抛 NetonHttpException。 */
    private suspend fun execute(
        ctx: HttpContext,
        channel: Channel,
        apiKey: String,
        rewritten: Rewritten,
        path: String,
        token: GatewayToken,
        group: String,
        startMs: Long,
    ): Unit? {
        val client = clientFor(channel)
        val request = NetonHttpRequest(
            method = NetonHttpMethod.Post,
            url = channel.baseUrl.trimEnd('/') + path,
            headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "application/json",
            ),
            body = NetonHttpBody.Json(rewritten.body),
        )

        if (!rewritten.stream) {
            val res = client.request(request)
            if (res.statusCode == 429 || res.statusCode >= 500) {
                throw NetonHttpException(NetonHttpError.Http(res.statusCode, "HTTP ${res.statusCode}", res.body))
            }
            if (res.statusCode !in 200..299) {
                recordLog(token, channel, rewritten, Usage(), group, startMs, "error", "upstream_${res.statusCode}", null)
                respondRaw(ctx, res.statusCode, res.body)
                return Unit
            }
            val ttfb = now() - startMs
            val usage = UsageExtractor.fromJson(res.body)
            respondRaw(ctx, 200, res.body)
            bill(token, channel, rewritten, usage, group, startMs, ttfb)
            return Unit
        }

        // 流式：首字节前的失败由 stream() 抛 Http 异常（可重试窗口）；开流后直通不回头
        val accumulator = SseUsageAccumulator()
        var ttfbMs: Long? = null
        ctx.response.status = HttpStatus.OK
        ctx.response.contentType = "text/event-stream; charset=utf-8"
        ctx.response.header("Cache-Control", "no-cache")
        ctx.response.header("X-Accel-Buffering", "no")
        try {
            ctx.response.stream {
                client.stream(request).collect { chunk ->
                    when (chunk) {
                        is NetonHttpStreamChunk.Bytes -> {
                            if (ttfbMs == null) ttfbMs = now() - startMs
                            writeChunk(chunk.bytes)
                            accumulator.feed(chunk.bytes.decodeToString())
                        }
                        is NetonHttpStreamChunk.Text -> {
                            if (ttfbMs == null) ttfbMs = now() - startMs
                            writeChunk(chunk.text)
                            accumulator.feed(chunk.text)
                        }
                        is NetonHttpStreamChunk.End -> {}
                    }
                }
            }
        } catch (e: NetonHttpException) {
            if (ttfbMs == null) throw e  // 首字节前：交给重试
            // 开流后中断：尽力计费收尾，不再重试
            log.warn("relay stream aborted mid-flight channel=${channel.id}: ${e.error}")
            bill(token, channel, rewritten, accumulator.usage(), group, startMs, ttfbMs, status = "partial")
            return Unit
        }
        bill(token, channel, rewritten, accumulator.usage(), group, startMs, ttfbMs)
        return Unit
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

    private suspend fun respondError(ctx: HttpContext, status: Int, code: String, message: String) {
        respondRaw(ctx, status, """{"error":{"message":"$message","type":"$code","code":"$code"}}""")
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
