package logic

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.produce
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
import table.ModelPriceTable
import table.UsageLogTable
import logic.protocol.ProtocolAdapter
import logic.protocol.OpenAiAdapter
import logic.protocol.AnthropicAdapter
import logic.protocol.GeminiAdapter
import logic.protocol.ProtocolCodecs
import logic.protocol.StreamStage
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

    suspend fun relay(ctx: HttpContext, userId: Long, tokenId: Long, inbound: ProtocolAdapter, path: String, rawBodyOverride: String? = null) {
        val startMs = now()
        val rawBody = rawBodyOverride ?: ctx.request.text()

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

        val candidates = RelayRouter.candidates(channelLogic.listEnabled(), requestModel, group, now())
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
            val transform = buildTransform(inbound, upstream, rawBody, channel, path)
            val key = channelLogic.pickKey(channel.id)
            if (key == null) { lastError = "channel ${channel.id} has no available key"; return@repeat }

            val rewritten = transform.rewritten
            val outcome: RelayOutcome? = try {
                execute(ctx, channel, key.id, key.apiKey, transform, path, token, group, startMs)
            } catch (e: NetonHttpException) {
                val err = e.error
                val retryable = when (err) {
                    is NetonHttpError.Http -> err.statusCode == 429 || err.statusCode >= 500
                    is NetonHttpError.Network, is NetonHttpError.Timeout -> true
                    else -> false
                }
                if (!retryable && err is NetonHttpError.Http) {
                    // 上游认证错（401/403）归咎于 Key；其它 4xx 是客户端问题，不计 Key 失败
                    if (err.statusCode == 401 || err.statusCode == 403) channelLogic.recordKeyFailure(key.id, "upstream_${err.statusCode}")
                    recordLog(token, channel, rewritten, Usage(), group, startMs, status = "error", errorCode = "upstream_${err.statusCode}", ttfbMs = null)
                    respondRaw(ctx, err.statusCode, err.body ?: inbound.errorBody("upstream_error", "upstream error ${err.statusCode}"))
                    return
                }
                lastError = err.toString().replace("\n", " ").take(160)
                // 429（限流）是暂时性的，不计入永久禁用；仅 5xx/网络/超时计 Key 失败（Codex 评审 #3）。
                // 429 改为给渠道加冷却退避（cooldown_until），冷却期内不再选它，避免立即重试撞同一限流上游。
                val is429 = (err as? NetonHttpError.Http)?.statusCode == 429
                if (is429) channelLogic.recordChannelCooldown(channel.id)
                else channelLogic.recordKeyFailure(key.id, lastError)
                log.warn("relay retry channel=${channel.id} err=$lastError")
                null
            }
            if (outcome != null) {
                // 仅完整成功清零 fail_count；partial（中断/上游 4xx）不当作 Key 成功
                if (outcome == RelayOutcome.SUCCESS) channelLogic.recordKeySuccess(key.id)
                return  // 已终态（计费与日志已在 execute 内完成）
            }
        }
        respondError(ctx, inbound, 503, "no_available_channel", lastError ?: "all channels failed")
    }

    /** 单次转发结果：SUCCESS=完整成功；PARTIAL=已响应但非完整成功（中断/上游语义错误），不改 Key 状态。 */
    private enum class RelayOutcome { SUCCESS, PARTIAL }

    private fun adapterForChannel(type: String): ProtocolAdapter? = when (type) {
        "openai_compatible", "azure_openai" -> OpenAiAdapter
        "anthropic" -> AnthropicAdapter
        "gemini" -> GeminiAdapter
        else -> null
    }

    /**
     * 组装转发变换（枢纽模型：OpenAI ChatCompletions 为内部规范）。
     * 同协议 = 恒等透传（保真，决策 A）；跨协议 = inbound→hub→upstream 组合，
     * openai 端各段恒等，Gemini↔Anthropic 经 hub 免费组合。
     */
    private fun buildTransform(
        inbound: ProtocolAdapter, upstream: ProtocolAdapter, rawBody: String, channel: Channel, path: String,
    ): RelayTransform {
        // 同协议：模型映射 + 协议原生改写，响应恒等透传
        if (inbound.id == upstream.id) {
            val rw = upstream.rewrite(rawBody, channel)
            val up = upstreamPathFor(upstream, path, rw.upstreamModel, rw.stream)
            return RelayTransform(rw, upstream, up, { it }, null)
        }
        // 跨协议：inbound → hub(OpenAI) → upstream
        val mapped = RequestRewriter.rewrite(rawBody, channel, injectStreamOptions = false)
        val hubBody = ProtocolCodecs.requestToHub(inbound.id)(mapped.body)
        // 上游是 OpenAI 时补 include_usage（流式尾包）
        val upstreamBody = if (upstream.id == "openai" && mapped.stream) {
            RequestRewriter.injectIncludeUsage(hubBody)
        } else {
            ProtocolCodecs.requestFromHub(upstream.id)(hubBody)
        }
        return RelayTransform(
            rewritten = Rewritten(upstreamBody, mapped.requestModel, mapped.upstreamModel, mapped.stream),
            upstream = upstream,
            upstreamPath = upstreamPathFor(upstream, path, mapped.upstreamModel, mapped.stream),
            transformResponseBody = { upstreamNative ->
                val hub = ProtocolCodecs.responseToHub(upstream.id)(upstreamNative)
                ProtocolCodecs.responseFromHub(inbound.id)(hub)
            },
            streamTransformerFactory = {
                val toHub = ProtocolCodecs.streamToHub(upstream.id, mapped.requestModel)
                val fromHub = ProtocolCodecs.streamFromHub(inbound.id, mapped.requestModel)
                ChainedStreamTransformer(listOfNotNull(toHub, fromHub))
            },
        )
    }

    /** 组合多个流式阶段：feed 链式；finish 时每阶段先消费上阶段 flush 输出再自己 flush。 */
    private class ChainedStreamTransformer(private val stages: List<StreamStage>) : StreamTransformer {
        override fun feed(chunk: String): String {
            var cur = chunk
            for (s in stages) {
                if (cur.isEmpty()) return ""
                cur = s.feed(cur)
            }
            return cur
        }
        override fun finish(): String {
            var carry = ""  // 上一阶段的 flush 输出，流入下一阶段
            for (s in stages) {
                val fed = if (carry.isNotEmpty()) s.feed(carry) else ""
                carry = fed + s.finish()
            }
            return carry
        }
    }

    /** 返回 SUCCESS/PARTIAL（已终态）；可重试失败抛 NetonHttpException（downstream 未提交）。 */
    private suspend fun execute(
        ctx: HttpContext,
        channel: Channel,
        keyId: Long,
        apiKey: String,
        transform: RelayTransform,
        path: String,
        token: GatewayToken,
        group: String,
        startMs: Long,
    ): RelayOutcome {
        val rewritten = transform.rewritten
        val upstream = transform.upstream
        val client = clientFor(channel)
        val request = NetonHttpRequest(
            method = NetonHttpMethod.Post,
            url = channel.baseUrl.trimEnd('/') + transform.upstreamPath,
            headers = upstream.upstreamHeaders(apiKey),
            body = NetonHttpBody.Json(rewritten.body),
        )

        if (!rewritten.stream) {
            val res = client.request(request)
            if (res.statusCode == 429 || res.statusCode >= 500) {
                throw NetonHttpException(NetonHttpError.Http(res.statusCode, "HTTP ${res.statusCode}", res.body))
            }
            if (res.statusCode !in 200..299) {
                // 401/403 = 凭证级失败，计 Key 失败（与流式/外层分类统一）；其它 4xx 是客户端问题
                if (res.statusCode == 401 || res.statusCode == 403) channelLogic.recordKeyFailure(keyId, "upstream_${res.statusCode}")
                recordLog(token, channel, rewritten, Usage(), group, startMs, "error", "upstream_${res.statusCode}", null)
                respondRaw(ctx, res.statusCode, res.body)  // 上游错误 body 原样（擦除 headers）
                return RelayOutcome.PARTIAL  // 上游语义错误：终态，不当 Key 成功
            }
            val ttfb = now() - startMs
            val usage = upstream.extractUsage(res.body)          // usage 从上游原生响应提取
            respondRaw(ctx, 200, transform.transformResponseBody(res.body))  // body 按需转协议
            bill(token, channel, rewritten, usage, group, startMs, ttfb)
            return RelayOutcome.SUCCESS
        }

        // 流式：先确认上游首字节再提交 downstream——否则 downstream 一旦提交（committed），
        // 首字节前上游失败时无法换渠道重试（会撞 ResponseAlreadyCommitted）。
        val accumulator = upstream.streamUsageAccumulator()
        val streamTransformer = transform.streamTransformerFactory?.invoke()
        return streamWithPreflight(ctx, client, request, accumulator, streamTransformer, token, channel, keyId, rewritten, group, startMs)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun streamWithPreflight(
        ctx: HttpContext, client: NetonHttpClient, request: NetonHttpRequest,
        accumulator: logic.protocol.StreamUsageAccumulator, streamTransformer: StreamTransformer?,
        token: GatewayToken, channel: Channel, keyId: Long, rewritten: Rewritten, group: String, startMs: Long,
    ): RelayOutcome = kotlinx.coroutines.coroutineScope {
        // producer 捕获上游异常存入 openError（不外抛，避免 cancel scope 抢占我们的重试异常）
        var openError: Throwable? = null
        val chunks = this@coroutineScope.produce<String>(capacity = 1) {
            try {
                client.stream(request).collect { chunk ->
                    when (chunk) {
                        is NetonHttpStreamChunk.Bytes -> send(chunk.bytes.decodeToString())
                        is NetonHttpStreamChunk.Text -> send(chunk.text)
                        is NetonHttpStreamChunk.End -> {}
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                openError = e
            }
        }
        // 等首块：channel 正常 close 且 openError 非空 = 首字节前失败（downstream 未提交，可重试）
        val firstResult = chunks.receiveCatching()
        if (firstResult.isClosed) {
            openError?.let { cause ->
                if (cause is NetonHttpException) throw cause
                throw NetonHttpException(NetonHttpError.Network(cause.message ?: "stream open failed", cause as? Throwable))
            }
            // 空流（无块无错）：视为成功空响应
        }
        val ttfbMs = now() - startMs
        ctx.response.status = HttpStatus.OK
        ctx.response.contentType = "text/event-stream; charset=utf-8"
        ctx.response.header("Cache-Control", "no-cache")
        ctx.response.header("X-Accel-Buffering", "no")
        var aborted = false
        var billed = false
        try {
            ctx.response.stream {
                suspend fun writeOut(text: String) {
                    accumulator.feed(text)  // 始终喂上游原文用于计费
                    if (streamTransformer != null) {
                        val c = streamTransformer.feed(text); if (c.isNotEmpty()) writeChunk(c)
                    } else writeChunk(text)
                }
                firstResult.getOrNull()?.let { writeOut(it) }  // 空流（首块即 close 无异常）则跳过
                for (text in chunks) writeOut(text)             // producer 捕获错误，此处仅正常收流
                // 终止判定：上游出错，或传输层结束但未见协议终止标记（[DONE]/message_stop/finishReason）= 上游截断。
                // 截断按 PARTIAL 收尾，且**不调 transformer.finish()**（不得伪造 [DONE]/message_stop 让客户端误判完整）。
                when {
                    openError != null -> {
                        aborted = true
                        log.warn("relay stream aborted mid-flight (upstream error) channel=${channel.id}: $openError")
                    }
                    !accumulator.terminalSeen() -> {
                        aborted = true
                        log.warn("relay stream ended without terminal marker (upstream truncated) channel=${channel.id}")
                    }
                    else -> streamTransformer?.finish()?.let { if (it.isNotEmpty()) writeChunk(it) }
                }
            }
        } catch (e: Throwable) {
            // 先停 producer：否则下游不再消费、producer 填满缓冲后永久挂在 send()，coroutineScope 等它导致请求不结束。
            chunks.cancel()
            // 响应已提交无法重试；尽力结算 partial（NonCancellable 防 cancelled 协程里 bill 的 DB 挂起立即丢账）。
            if (!billed) {
                billed = true
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    kotlinx.coroutines.withTimeoutOrNull(5000) {
                        bill(token, channel, rewritten, accumulator.usage(), group, startMs, ttfbMs, status = "partial")
                    } ?: log.warn("relay partial-bill timed out on disconnect channel=${channel.id}")
                }
            }
            when {
                e is kotlinx.coroutines.CancellationException -> throw e  // 结构化并发：必须重抛
                isDownstreamDisconnect(e) -> {
                    log.warn("relay stream downstream disconnect channel=${channel.id}: ${e::class.simpleName}")
                    return@coroutineScope RelayOutcome.PARTIAL
                }
                else -> {
                    // 非断连（codec bug/内部错误等）：不伪装成断连，ERROR 暴露，重抛（框架 committed handler 收尾）
                    log.error("relay stream internal error channel=${channel.id}", cause = e)
                    throw e
                }
            }
        }
        // 上游截断/出错（非下游断连——下游断连在 catch 内已 return）：按网络失败策略计 Key 失败。
        // 首字节后不重试渠道（外层对 PARTIAL 直接 return），只累计失败用于自动禁用/轮转。
        if (aborted) channelLogic.recordKeyFailure(keyId, "stream_truncated")
        if (!billed) {
            bill(token, channel, rewritten, accumulator.usage(), group, startMs, ttfbMs,
                 status = if (aborted) "partial" else if (accumulator.usage() != null) "ok" else "partial")
        }
        if (aborted) RelayOutcome.PARTIAL else RelayOutcome.SUCCESS
    }

    /** 转发变换：请求体/上游路径已终态；响应体/流按需转协议（同协议为恒等）。 */
    class RelayTransform(
        val rewritten: Rewritten,
        val upstream: ProtocolAdapter,
        val upstreamPath: String,
        val transformResponseBody: (String) -> String,
        val streamTransformerFactory: (() -> StreamTransformer)?,
    )

    /** 上游端点路径：openai 保留入站路径（含 embeddings），anthropic 固定 /v1/messages，
     *  gemini 用上游模型名 + action 重建。 */
    private fun upstreamPathFor(upstream: ProtocolAdapter, inboundPath: String, upstreamModel: String, stream: Boolean): String =
        when (upstream.id) {
            "anthropic" -> "/v1/messages"
            "gemini" -> "/v1beta/models/$upstreamModel:" + if (stream) "streamGenerateContent?alt=sse" else "generateContent"
            else -> if (inboundPath.startsWith("/v1/")) inboundPath else "/v1/chat/completions"
        }

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

        // 账务全或无：usage_log + 余额扣减 + 台账 + token.quotaUsed 同一事务提交。
        // 余额/quotaUsed 均用 SQL 自增（x = x + delta），行级锁串行化并发结算，无 version 竞争、无重试丢单。
        neton.database.dbContext().transaction {
            val logId = recordLog(token, channel, rewritten, effectiveUsage, group, startMs, status, null, ttfbMs, charged, cost)
            if (charged > 0) {
                quotaLogic.debitInTx(token.userId, -charged, "consume", "usage:$logId")
                // token.quotaUsed 原子自增；deleted=0 守卫：请求中途被删（硬删/软删）时 0 行受影响。
                val updated = neton.database.dbContext().execute(
                    "UPDATE gateway_tokens SET quota_used = quota_used + :c WHERE id = :id AND deleted = 0",
                    mapOf("c" to charged, "id" to token.id),
                )
                if (updated == 0L) {
                    // token 请求中途被删：账户级三表照提交（收入不丢），quotaUsed 略过（per-token 计数已无意义）。
                    // 不回滚（回滚=已服务请求不计费=收入泄漏）；不变量降级为账户级三项相等。
                    log.warn("relay bill: token deleted mid-request; account settled, quotaUsed skipped tokenId=${token.id} userId=${token.userId} logId=$logId charged=$charged")
                } else token.quotaBudget?.let { budget ->
                    // 软预算告警：并发下预检（quotaUsed>=budget 拒）无法防超支——已服务请求照扣（后付费）。
                    // 硬预算（reserve/finalize：请求前条件预留、完成后按实际结算释放差额）为独立设计，未落地。
                    if (token.quotaUsed + charged > budget) {
                        log.warn("gateway token over soft budget tokenId=${token.id} userId=${token.userId} budget=$budget used=${token.quotaUsed + charged}（硬预算待 reserve/finalize）")
                    }
                }
            }
        }
    }

    /** 识别下游断连（IO/传输层）异常：类名/消息含 IO/Closed/Broken/pipe/reset/Channel。 */
    private fun isDownstreamDisconnect(e: Throwable): Boolean {
        val n = (e::class.simpleName ?: "") + " " + (e.message ?: "")
        return listOf("IOException", "Closed", "Broken", "pipe", "reset", "ClosedWriteChannel", "ChannelWrite")
            .any { n.contains(it, ignoreCase = true) }
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
