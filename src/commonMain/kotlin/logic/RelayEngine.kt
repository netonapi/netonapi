package logic

import neton.core.http.HttpHeaders

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.produce
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import model.Channel
import model.GatewayToken
import model.ModelPrice
import model.UsageLog
import neton.core.http.HttpContext
import neton.core.http.HttpStatus
import neton.http.client.*
import neton.logging.Logger
import table.ModelPriceTable
import table.UsageLogTable
import logic.protocol.ProtocolAdapter
import logic.protocol.OpenAiAdapter
import logic.protocol.AzureOpenAiAdapter
import logic.protocol.ModelRename
import logic.protocol.AnthropicAdapter
import logic.protocol.GeminiAdapter
import logic.protocol.ProtocolCodecs
import logic.protocol.StreamStage
import neton.database.dsl.*
import kotlin.random.Random

/**
 * 中转引擎（M1b）：同协议透传改写 → 渠道路由重试 → 直通/计费/日志。
 * 每请求由 RelayController 构造（controller 每请求实例化），渠道 HTTP client 缓存在 companion（[ChannelClientCache]，会驱逐与回收）。
 */
class RelayEngine(
    private val log: Logger,
    private val channelLogic: ChannelLogic,
    private val tokenLogic: TokenLogic,
    private val quotaLogic: QuotaLogic,
    private val settlementLogic: SettlementLogic,
    private val rateLimiter: RateLimiter,
    /** 能力验证（V016）。为空时从应用上下文取；都没有则视为一律未验证，不启用占位兼容。 */
    private var verificationLogic: ChannelVerificationLogic? = null,
) {

    /** 本次请求的资金占位（RelayEngine 每请求实例化，故可用字段持有）。 */
    private var settlement: model.Settlement? = null

    /** 已占用的并发额度（非 null 表示需要在收尾时释放，否则额度泄漏）。 */
    private var concurrencyHeld: Int? = null
    private var tokenIdForLimits: Long = 0

    /**
     * 中转入口。并发额度一旦占用就必须归还——用 finally 覆盖所有 return / 异常路径，
     * 否则额度会随失败请求泄漏，最终把令牌永久卡死在「并发已满」。
     */
    /**
     * 非 JSON 进出的端点（音频）用的附加信息：
     * [bodyBytes]/[contentType] = 原样转发的 multipart 请求体（此时 rawBody 只是给路由/计费看的合成 JSON）；
     * [binaryResponse] = 上游回二进制（TTS 音频），走字节流收下来原样回；
     * [requestUsage] = 上游不给 usage 时按请求侧算的用量（TTS 按输入字符数计）。
     */
    class RelayExtras(
        val bodyBytes: ByteArray? = null,
        val contentType: String? = null,
        val binaryResponse: Boolean = false,
        val requestUsage: Usage? = null,
    )
    private var extras: RelayExtras = RelayExtras()

    suspend fun relay(ctx: HttpContext, userId: Long, tokenId: Long, inbound: ProtocolAdapter, path: String, rawBodyOverride: String? = null, extras: RelayExtras = RelayExtras()) {
        this.extras = extras
        try {
            relayInner(ctx, userId, tokenId, inbound, path, rawBodyOverride)
        } finally {
            concurrencyHeld?.let { rateLimiter.release(tokenIdForLimits, it) }
            concurrencyHeld = null
        }
    }

    private suspend fun relayInner(ctx: HttpContext, userId: Long, tokenId: Long, inbound: ProtocolAdapter, path: String, rawBodyOverride: String? = null) {
        val startMs = now()
        if (verificationLogic == null) verificationLogic = ctx.getApplicationContext()?.getOrNull(ChannelVerificationLogic::class)
        val rawBody = rawBodyOverride ?: ctx.request.text()

        val token = tokenLogic.get(tokenId)
        if (token == null || token.status != 1) return respondError(ctx, inbound, 401, "invalid_token", "token not found or disabled")

        // 令牌 IP 白名单：此前只有字段没有校验（宣称有、实际不生效比没有更危险）
        if (token.allowedIps.isNotBlank()) {
            val ip = clientIp(ctx)
            if (!NetGuard.ipAllowed(token.allowedIps, ip)) {
                log.warn("relay rejected by token ip allowlist tokenId=${token.id} userId=$userId ip=$ip")
                return respondError(ctx, inbound, 403, "ip_not_allowed", "client IP is not allowed for this token")
            }
        }

        // 速率限制：无限流时单用户即可打爆上游配额与成本
        when (rateLimiter.acquire(token.id, token.rpmLimit, token.tpmLimit, token.concurrencyLimit, now())) {
            RateLimiter.Decision.RPM_EXCEEDED ->
                return respondError(ctx, inbound, 429, "rate_limit_exceeded", "requests per minute limit reached")
            RateLimiter.Decision.TPM_EXCEEDED ->
                return respondError(ctx, inbound, 429, "rate_limit_exceeded", "tokens per minute limit reached")
            RateLimiter.Decision.CONCURRENCY_EXCEEDED ->
                return respondError(ctx, inbound, 429, "rate_limit_exceeded", "concurrent request limit reached")
            RateLimiter.Decision.ALLOWED -> { concurrencyHeld = token.concurrencyLimit; tokenIdForLimits = token.id }
        }

        // 计费组：解析一次，路由与计价共用同一个结果（此前路由按名字匹配、计价再查一次，
        // 中间夹着 `?: "default"` 与 `?: "1.0"` 两级静默回落 —— 组被改名/删除时请求照常放行、
        // 倍率悄悄变 1.0，钱少收而日志里一行痕迹都没有）。
        val resolution = when (val r = BillingGroupResolver.resolve(userId, token.groupOverride)) {
            is GroupResolution.Rejected -> {
                val detail = "relay billing group rejected user=$userId token=$tokenId " +
                    "reason=${r.reason} detail=${r.message}"
                // 5xx 类的拒绝必须 ERROR：respondError 不抛异常，框架那条「5xx → ERROR + 落
                // infra_api_error_logs」的路径走不到，日志级别是运营唯一的告警面（见 GroupReject）。
                // NOT_ALLOWED(403) 是部署决策、不是故障，留 WARN，免得把告警噪声化。
                if (r.reason.httpStatus >= 500) log.error(detail) else log.warn(detail)
                return respondError(ctx, inbound, r.reason.httpStatus, r.reason.code, r.message)
            }
            is GroupResolution.Resolved -> r
        }
        val billingGroup = resolution.group
        // 往下 execute/recordLog/bill 一路传的都是这个字符串：必须是稳定 code，不能是可改的显示名，
        // 否则管理员改个显示名就能让渠道匹配静默失配。
        val group = billingGroup.code
        // 归因（V007）：这个组是哪一层给的。上面那个 when 此前写的是 `-> r.group`，source 当场
        // 被扔掉 —— 而 GroupSource 的 KDoc 第一句就是「进日志：错账排查第一个要问的就是来源」。
        // 它跟着 group 走到三处：reserve（固化进结算行）、recordLog（落进 usage_log）、bill
        // （毛利倒挂告警）。那三处此前都只拿得到倍率、拿不到来源，于是「ratio=3.0」到底是
        // 谈判价配错了还是标准价配错了，无从判断。
        val groupSource = resolution.source
        val requestModel = runCatching { RequestRewriter.rewrite(rawBody, DUMMY).requestModel }.getOrElse {
            return respondError(ctx, inbound, 400, "invalid_request_error", "request body is not valid JSON")
        }
        if (requestModel.isEmpty()) return respondError(ctx, inbound, 400, "invalid_request_error", "model is required")

        // 令牌模型白名单
        if (token.allowedModels.isNotBlank() &&
            !token.allowedModels.split(',').map { it.trim() }.contains(requestModel)
        ) return respondError(ctx, inbound, 403, "model_not_allowed", "model '$requestModel' not allowed for this token")

        // 端点能力路由：/v1/embeddings 之类只能落到声明了该能力的渠道（否则上游必然 404，钱已预留、日志已脏）
        val capability = RelayRouter.capabilityFor(path)
        val enabledChannels = channelLogic.listEnabled()
        var candidates = RelayRouter.candidates(enabledChannels, requestModel, group, now(), capability)
        // 模型名推理尾缀（V017，§4.3）：完整名字没有渠道、且有开启了该开关的渠道服务去掉尾缀后的模型时，
        // 按基础模型路由与计价，推理意图按客户端协议写进请求体
        if (candidates.isEmpty()) {
            val parsed = neton.ai.protocol.convert.ReasoningSuffix.parse(requestModel)
            val reasoning = parsed.reasoning
            if (reasoning != null) {
                val suffixed = RelayRouter.candidates(enabledChannels.filter { it.reasoningSuffix == 1 }, parsed.model, group, now(), capability)
                val protocol = if (path == RESPONSES_PATH) logic.protocol.ResponsesBridge.PROTOCOL else inbound.id
                val rewrittenBody = if (suffixed.isEmpty()) null
                    else runCatching { logic.protocol.ProtocolCodecs.withReasoning(protocol, rawBody, parsed.model, reasoning) }.getOrNull()
                if (rewrittenBody != null) {
                    log.info("relay reasoning suffix model=$requestModel base=${parsed.model} reasoning=$reasoning")
                    return relayInner(ctx, userId, tokenId, inbound, path, rewrittenBody)
                }
            }
        }
        if (candidates.isEmpty()) {
            val reason = RelayRouter.noCandidateReason(enabledChannels, requestModel, group, now(), capability)
            log.warn("relay no candidate model=$requestModel capability=$capability group=$group reason=$reason")
            return if (reason.retryable) {
                respondError(ctx, inbound, 503, "no_available_channel", "no channel available for model '$requestModel'")
            } else {
                // 配置不变就永远不会可用（模型没配 / 能力没声明 / 分组不通）→ 404：回 503 客户端会一直重试撞墙
                respondError(
                    ctx, inbound, 404, "model_not_found",
                    "model '$requestModel' is not available on this endpoint",
                )
            }
        }

        // ── 资金占位（V004）：预留成功才允许调上游；无价/无输出上限一律拒绝，不免费放行 ──
        // 双轨计价：官方价 = 成本轨，售价（saleOverride 或官方价 × 全局加价率）= 收入轨。
        // 定价数据非法在入口 fail-fast 拒绝——坏价一旦进了 reserve，结算时就只能猜价或转人工。
        val officialPrice = ModelPriceTable.oneWhere { ModelPrice::model eq requestModel }
        // 倍率取自上面那一次解析（同一行数据），不再按名字重查、也不再回落 1.0。
        val ratio = billingGroup.ratio
        val markup = PricingPolicy.current().markup
        val salePrice = officialPrice?.let { op ->
            runCatching { SalePricing.resolve(op, markup) }.getOrElse { e ->
                log.error("CRITICAL model pricing invalid model=$requestModel: ${e.message}")
                // 500 而非 400：请求本身没问题，是我们的定价数据坏了——归成客户端错误会掩盖运营事故
                return respondError(
                    ctx, inbound, 500, "model_pricing_invalid",
                    "pricing config for model '$requestModel' is invalid",
                )
            }
        }
        val audio = path.startsWith("/v1/audio/")
        val reserveAmount = when (val rc = ReserveCalc.compute(rawBody, salePrice?.let { PricingTiers.mostExpensive(it) }, ratio, inputUpperBytes = extras.bodyBytes?.size, noOutput = audio)) {
            is ReserveCalc.Result.NoPrice ->
                return respondError(ctx, inbound, 400, "model_not_priced", "model '$requestModel' has no pricing configured")
            is ReserveCalc.Result.NoOutputCap ->
                return respondError(ctx, inbound, 400, "output_cap_required", "max_tokens is required for model '$requestModel'")
            is ReserveCalc.Result.Ok -> rc
        }
        // 客户端幂等键：先校验原始字节长度（不截断、不哈希超长值），再存定长 SHA-256
        val idemRaw = ctx.request.header("Idempotency-Key")
        if (idemRaw != null && idemRaw.encodeToByteArray().size !in 1..255) {
            return respondError(ctx, inbound, 400, "invalid_idempotency_key", "Idempotency-Key must be 1..255 bytes")
        }
        val idem = idemRaw?.let { neton.security.digest.Sha256.hex(it) }
        val reserved = settlementLogic.reserve(
            userId = userId, tokenId = tokenId, requestModel = requestModel, endpoint = path,
            reserveAmount = reserveAmount.amount,
            pricingSnapshot = pricingSnapshot(salePrice, officialPrice, ratio),
            groupCode = group, groupSource = groupSource.name,
            estimated = reserveAmount.estimated,
            clientIdemKey = idem,
            requestFingerprint = idem?.let { requestFingerprint(ctx, path, rawBody) },
        )
        settlement = when (reserved) {
            is SettlementLogic.ReserveResult.Ok -> reserved.settlement
            is SettlementLogic.ReserveResult.Duplicate ->
                return respondError(ctx, inbound, 409, "idempotency_conflict", "this Idempotency-Key is already in use")
            SettlementLogic.ReserveResult.InsufficientBalance ->
                return respondError(ctx, inbound, 402, "insufficient_quota", "account balance insufficient for this request")
            SettlementLogic.ReserveResult.InsufficientBudget ->
                return respondError(ctx, inbound, 429, "insufficient_quota", "token budget exhausted")
            SettlementLogic.ReserveResult.TokenUnavailable ->
                return respondError(ctx, inbound, 401, "invalid_token", "token not found or disabled")
        }

        val responsesRejection: String? by lazy { logic.protocol.ResponsesBridge.rejection(rawBody) }
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
            // Images / 音频 / rerank 只能打 OpenAI 系上游；Responses 无状态请求可经 hub 桥接到其他上游
            if (path in OPENAI_ONLY_PATHS && upstream.id != "openai") {
                val why = if (path == RESPONSES_PATH && ProtocolCodecs.formatOf(upstream.id) != null) responsesRejection else "does not serve $path"
                if (why != null) {
                    lastError = "channel ${channel.id} (${channel.type}) $why"
                    return@repeat
                }
            }
            val transform = try {
                buildTransform(inbound, upstream, rawBody, channel, path, userId, tokenId)
            } catch (e: neton.ai.protocol.diag.ConversionRejectedException) {
                lastError = "channel ${channel.id} (${channel.type}) loss policy ${channel.lossPolicy} rejected: " +
                    e.violations.joinToString(",") { it.code }
                log.info("relay conversion rejected $lastError")
                return@repeat
            }
            val key = channelLogic.pickKey(channel.id)
            if (key == null) { lastError = "channel ${channel.id} has no available key"; return@repeat }

            // T2：记录「已向上游发出」——崩溃后不可自动退款（可能已产生真实费用），由 worker TTL 转人工。
            // 渠道成本折扣此刻并入快照；重试换渠道则刷新为本次真正打的上游（否则成本记在没打过的那家头上）。
            settlement?.let { st ->
                val snap = pricingSnapshot(salePrice, costBasisFor(transform.rewritten, officialPrice), ratio, channel.costDiscount)
                settlementLogic.markUpstreamStarted(st.settlementId, channel.id, snap)
                settlement = st.copy(pricingSnapshot = snap)
            }

            val rewritten = transform.rewritten
            val outcome: RelayOutcome? = try {
                execute(ctx, channel, key.id, key.apiKey, transform, path, token, group, groupSource, startMs)
            } catch (e: HttpClientException) {
                val err = e.error
                val retryable = when (err) {
                    is HttpClientError.Http -> err.statusCode == 429 || err.statusCode >= 500
                    is HttpClientError.Network, is HttpClientError.Timeout -> true
                    else -> false
                }
                if (!retryable && err is HttpClientError.Http) {
                    // 上游认证错（401/403）归咎于 Key；其它 4xx 是客户端问题，不计 Key 失败
                    if (err.statusCode == 401 || err.statusCode == 403) channelLogic.recordKeyFailure(key.id, "upstream_${err.statusCode}")
                    recordLog(token, channel, rewritten, Usage(), group, groupSource, startMs, status = "error", errorCode = "upstream_${err.statusCode}", ttfbMs = null)
                    respondRaw(ctx, err.statusCode, err.body?.let { transform.errorBodyFor(it, err.statusCode) } ?: inbound.errorBody("upstream_error", "upstream error ${err.statusCode}"))
                    return
                }
                val forbidden = NetGuard.rejectionCode(err)
                if (forbidden != null) {
                    // 渠道配置指向出站策略不允许的目标（或 DNS 现在指向内网）：一个包都没发出去，不是 Key 的错，
                    // 不计 Key 失败；换下一个候选渠道。
                    lastError = "channel ${channel.id} upstream target refused: $forbidden"
                    log.warn("relay target refused channel=${channel.id} code=$forbidden detail=${err.message.take(200)}")
                    return@repeat
                }
                lastError = err.toString().replace("\n", " ").take(160)
                // 429（限流）是暂时性的，不计入永久禁用；仅 5xx/网络/超时计 Key 失败（Codex 评审 #3）。
                // 429 改为给渠道加冷却退避（cooldown_until），冷却期内不再选它，避免立即重试撞同一限流上游。
                val is429 = (err as? HttpClientError.Http)?.statusCode == 429
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
        // 全部候选在首字节前失败：我们**亲眼观察到**未产生 token 费用，释放预留是安全的
        // （区别于崩溃——那种情况不知上游是否已计费，走 worker TTL → 人工，见 C2）
        settlement?.let { s ->
            val from = bySettlementStatus(s.settlementId)
            settlementLogic.requestRelease(s.settlementId, from)
            settlementLogic.release(s.settlementId)
        }
        respondError(ctx, inbound, 503, "no_available_channel", lastError ?: "all channels failed")
    }

    /** 单次转发结果：SUCCESS=完整成功；PARTIAL=已响应但非完整成功（中断/上游语义错误），不改 Key 状态。 */
    private enum class RelayOutcome { SUCCESS, PARTIAL }


    /**
     * 组装转发变换（枢纽模型：OpenAI ChatCompletions 为内部规范）。
     * 同协议 = 恒等透传（保真，决策 A）；跨协议 = inbound→hub→upstream 组合，
     * openai 端各段恒等，Gemini↔Anthropic 经 hub 免费组合。
     */
    private suspend fun buildTransform(
        inbound: ProtocolAdapter, upstream: ProtocolAdapter, rawBody: String, channel: Channel, path: String,
        userId: Long, tokenId: Long,
    ): RelayTransform {
        // 同协议：模型映射 + 协议原生改写，响应恒等透传
        if (inbound.id == upstream.id) {
            // Responses / Images 不认 stream_options（会 400）；Responses 的流本来就在终态事件里带 usage
            val rw = if (path in OPENAI_ONLY_PATHS) RequestRewriter.rewrite(rawBody, channel, injectStreamOptions = false)
                     else upstream.rewrite(rawBody, channel)
            val up = upstreamPathFor(upstream, channel, path, rw.upstreamModel, rw.stream)
            // 映射过模型名的，响应里的 model 改回客户端请求的名字（见 ModelRename）；没映射的保持恒等透传
            val renamed = rw.upstreamModel != rw.requestModel
            return RelayTransform(
                rewritten = rw, upstream = upstream, inbound = inbound, upstreamPath = up,
                transformResponseBody = if (renamed) { b -> ModelRename.json(b, rw.upstreamModel, rw.requestModel) } else { b -> b },
                streamTransformerFactory = if (renamed) {
                    { ChainedStreamTransformer(listOf(ModelRename.stage(rw.upstreamModel, rw.requestModel))) }
                } else null,
            )
        }
        // 跨协议：inbound → hub(OpenAI) → upstream。/v1/responses 的入站格式是 Responses（不是 Chat）
        val inboundProtocol = if (path == RESPONSES_PATH) logic.protocol.ResponsesBridge.PROTOCOL else inbound.id
        val mapped = RequestRewriter.rewrite(rawBody, channel, injectStreamOptions = false)
        // 损耗策略（V017，§4.2）：违反时抛 ConversionRejectedException，外层换下一个渠道
        val bypassOn = upstream.id == "gemini" && ThoughtSignatureBypassPolicy.enabled &&
            (verificationLogic?.isVerified(channel.id, ChannelVerificationLogic.CAPABILITY_GEMINI_SIGNATURE_BYPASS, mapped.upstreamModel) ?: false)
        val policy = ConversionPolicy.of(channel, signatureBypass = bypassOn)
        val diagnostics = mutableListOf<neton.ai.protocol.diag.Diagnostic>()
        val hubBody = ProtocolCodecs.convertRequest(mapped.body, inboundProtocol, "openai", policy, diagnostics)
        // 上游是 Gemini：thought signature 的跨请求回传与缺失处理（neton-docs spec/ai.md §4.5，gateway 文档 §4.6）
        val vault = if (upstream.id == "gemini") ThoughtSignatureVault(
            ThoughtSignatureVault.Binding(tokenId, userId, channel.type, mapped.upstreamModel, channel.id),
            keepExtraContent = inbound.id == "openai" && ThoughtSignatureVaults.echoToOpenAiClients,
        ) else null
        // 上游是 OpenAI 时补 include_usage（流式尾包）
        val upstreamBody = if (upstream.id == "openai" && mapped.stream) {
            RequestRewriter.injectIncludeUsage(hubBody)
        } else if (vault != null) {
            geminiUpstreamBody(vault.resolve(hubBody), channel, mapped.upstreamModel, bypassOn, policy, diagnostics)
        } else {
            ProtocolCodecs.convertRequest(hubBody, "openai", upstream.id, policy, diagnostics)
        }
        if (diagnostics.isNotEmpty()) {
            log.info("relay conversion channel=${channel.id} $inboundProtocol->${upstream.id} policy=${channel.lossPolicy} " +
                "diagnostics=${diagnostics.joinToString(",") { it.code + "@" + it.path }}".take(1000))
        }
        return RelayTransform(
            rewritten = Rewritten(upstreamBody, mapped.requestModel, mapped.upstreamModel, mapped.stream),
            upstream = upstream,
            inbound = inbound,
            upstreamPath = upstreamPathFor(upstream, channel, path, mapped.upstreamModel, mapped.stream),
            transformResponseBody = { upstreamNative ->
                val hub = ProtocolCodecs.responseToHub(upstream.id)(upstreamNative)
                val signed = vault?.issue(hub) ?: hub
                ProtocolCodecs.responseFromHub(inboundProtocol)(ModelRename.json(signed, mapped.upstreamModel, mapped.requestModel))
            },
            streamTransformerFactory = {
                val toHub = ProtocolCodecs.streamToHub(upstream.id, mapped.requestModel)
                val rename = if (mapped.upstreamModel != mapped.requestModel) ModelRename.stage(mapped.upstreamModel, mapped.requestModel) else null
                val fromHub = ProtocolCodecs.streamFromHub(inboundProtocol, mapped.requestModel)
                ChainedStreamTransformer(listOfNotNull(toHub, vault?.stage(), rename, fromHub))
            },
            signatureVault = vault,
            inboundProtocol = inboundProtocol,
        )
    }

    /**
     * hub → Gemini 请求，处理 thought signature：真实签名原样写回；缺失时只在
     * 「开关开启 + 该渠道与模型有有效的验证记录」时补官方占位值（spec §4.5.2），否则记为缺失。
     */
    private fun geminiUpstreamBody(
        hubBody: String, channel: Channel, upstreamModel: String, bypass: Boolean,
        policy: neton.ai.protocol.diag.LossPolicy, all: MutableList<neton.ai.protocol.diag.Diagnostic>,
    ): String {
        val diagnostics = logic.protocol.SignatureDiagnostics()
        val body = logic.protocol.GeminiRequestConverter.convert(hubBody, logic.protocol.GeminiRequestOptions(signatureBypass = bypass), diagnostics, policy, all)
        if (diagnostics.bypassed > 0) {
            log.info("${logic.protocol.GeminiThoughtSignatures.DIAG_BYPASSED} channel=${channel.id} model=$upstreamModel steps=${diagnostics.bypassed}")
        }
        if (diagnostics.missingInCurrentTurn > 0) {
            log.warn("${logic.protocol.GeminiThoughtSignatures.DIAG_MISSING} channel=${channel.id} model=$upstreamModel steps=${diagnostics.missingInCurrentTurn} (upstream may reject with 400)")
        }
        return body
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

    /** 返回 SUCCESS/PARTIAL（已终态）；可重试失败抛 HttpClientException（downstream 未提交）。 */
    private suspend fun execute(
        ctx: HttpContext,
        channel: Channel,
        keyId: Long,
        apiKey: String,
        transform: RelayTransform,
        path: String,
        token: GatewayToken,
        group: String,
        groupSource: GroupSource,
        startMs: Long,
    ): RelayOutcome = withChannelClient(channel) { client ->
        executeOn(client, ctx, channel, keyId, apiKey, transform, path, token, group, groupSource, startMs)
    }

    private suspend fun executeOn(
        client: HttpClient,
        ctx: HttpContext,
        channel: Channel,
        keyId: Long,
        apiKey: String,
        transform: RelayTransform,
        path: String,
        token: GatewayToken,
        group: String,
        groupSource: GroupSource,
        startMs: Long,
    ): RelayOutcome {
        val rewritten = transform.rewritten
        val upstream = transform.upstream
        // 同协议时把客户端的 beta 头带给上游：Claude Code 靠 anthropic-beta 开 interleaved-thinking、
        // 1M 上下文、细粒度工具流等能力，丢了它请求会以另一种形状被服务。跨协议没有对应物，不带。
        val betaHeaders = if (transform.inbound.id == upstream.id) {
            listOf("anthropic-beta", "OpenAI-Beta").mapNotNull { h -> ctx.request.header(h)?.let { h to it } }.toMap()
        } else emptyMap()
        val upstreamUrl = channel.baseUrl.trimEnd('/') + transform.upstreamPath
        val upstreamBody = upstream.upstreamBody(rewritten.body, rewritten.upstreamModel, rewritten.stream)
        // 托管上游（Bedrock / Vertex）的鉴权头依赖 url 与 body（签名 / 换 token），所以在 body 定型之后才算
        val authHeaders = try { upstream.requestHeaders(apiKey, "POST", upstreamUrl, upstreamBody, channel) } catch (e: IllegalArgumentException) {
            throw HttpClientException(HttpClientError.Http(401, e.message ?: "credential error", upstream.errorBody("authentication_error", e.message ?: "credential error")))
        }
        val multipart = extras.bodyBytes
        val request = if (multipart != null) {
            // multipart 原样转发：只把 model 字段换成映射后的上游名；Content-Type 必须是客户端那个（带 boundary）
            val boundary = logic.protocol.MultipartForm.boundary(extras.contentType)
            val bytes = if (boundary != null && rewritten.upstreamModel != rewritten.requestModel)
                logic.protocol.MultipartForm.replaceField(multipart, boundary, "model", rewritten.upstreamModel) else multipart
            HttpClientRequest(
                method = HttpClientMethod.Post, url = upstreamUrl,
                headers = HttpHeaders.from(authHeaders.filterKeys { !it.equals("Content-Type", ignoreCase = true) } + betaHeaders),
                body = HttpClientBody.Bytes(bytes, extras.contentType ?: "multipart/form-data"),
            )
        } else HttpClientRequest(
            method = HttpClientMethod.Post,
            url = upstreamUrl,
            headers = HttpHeaders.from(authHeaders + betaHeaders),
            body = HttpClientBody.Json(upstreamBody),
        )

        if (extras.binaryResponse) {
            // 二进制响应（TTS）：非 2xx 由 stream() 抛带 body 的异常，外层按状态分类；2xx 收齐字节原样回
            var contentType: String? = null
            val chunks = ArrayList<ByteArray>()
            client.stream(request).collect { chunk ->
                when (chunk) {
                    is HttpClientStreamChunk.Start -> contentType = chunk.headers["Content-Type"] ?: chunk.headers["content-type"]
                    is HttpClientStreamChunk.Bytes -> chunks += chunk.bytes
                    is HttpClientStreamChunk.Text -> chunks += chunk.text.encodeToByteArray()
                    is HttpClientStreamChunk.End -> if (contentType == null) contentType = chunk.finalHeaders["Content-Type"] ?: chunk.finalHeaders["content-type"]
                }
            }
            val total = ByteArray(chunks.sumOf { it.size }); var off = 0
            for (c in chunks) { c.copyInto(total, off); off += c.size }
            val ttfb = now() - startMs
            ctx.response.status = HttpStatus.OK
            ctx.response.contentType = contentType ?: "application/octet-stream"
            ctx.response.write(total)
            bill(token, channel, rewritten, extras.requestUsage, group, groupSource, startMs, ttfb)
            return RelayOutcome.SUCCESS
        }

        if (!rewritten.stream) {
            val res = client.request(request)
            if (res.statusCode == 429 || res.statusCode >= 500) {
                throw HttpClientException(HttpClientError.Http(res.statusCode, "HTTP ${res.statusCode}", res.body))
            }
            if (res.statusCode !in 200..299) {
                // 401/403 = 凭证级失败，计 Key 失败（与流式/外层分类统一）；其它 4xx 是客户端问题
                if (res.statusCode == 401 || res.statusCode == 403) channelLogic.recordKeyFailure(keyId, "upstream_${res.statusCode}")
                recordLog(token, channel, rewritten, Usage(), group, groupSource, startMs, "error", "upstream_${res.statusCode}", null)
                respondRaw(ctx, res.statusCode, transform.errorBodyFor(res.body, res.statusCode))  // 同协议原样、跨协议改写（擦除 headers）
                return RelayOutcome.PARTIAL  // 上游语义错误：终态，不当 Key 成功
            }
            val ttfb = now() - startMs
            val usage = upstream.extractUsage(res.body) ?: extras.requestUsage  // usage 从上游原生响应提取；没有就用请求侧估的
            if (multipart != null) {
                // 转写结果按上游的内容类型回（response_format=text 时是纯文本，不是 JSON）
                respondRaw(ctx, 200, res.body, res.headers["Content-Type"] ?: res.headers["content-type"])
            } else {
                val out = transform.transformResponseBody(res.body)  // body 按需转协议
                transform.signatureVault?.let { v -> runCatching { v.flush() }.onFailure { log.warn("signature vault flush failed: ${it.message}") } }
                respondRaw(ctx, 200, out)
            }
            bill(token, channel, rewritten, usage, group, groupSource, startMs, ttfb)
            return RelayOutcome.SUCCESS
        }

        // 流式：先确认上游首字节再提交 downstream——否则 downstream 一旦提交（committed），
        // 首字节前上游失败时无法换渠道重试（会撞 ResponseAlreadyCommitted）。
        val accumulator = upstream.streamUsageAccumulator(rewritten.body)
        val streamTransformer = transform.streamTransformerFactory?.invoke()
        return streamWithPreflight(ctx, client, request, accumulator, streamTransformer, token, channel, keyId, rewritten, group, groupSource, startMs, upstream, transform.signatureVault)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun streamWithPreflight(
        ctx: HttpContext, client: HttpClient, request: HttpClientRequest,
        accumulator: logic.protocol.StreamUsageAccumulator, streamTransformer: StreamTransformer?,
        token: GatewayToken, channel: Channel, keyId: Long, rewritten: Rewritten, group: String,
        groupSource: GroupSource, startMs: Long, upstreamAdapter: ProtocolAdapter,
        signatureVault: ThoughtSignatureVault? = null,
    ): RelayOutcome = kotlinx.coroutines.coroutineScope {
        // producer 捕获上游异常存入 openError（不外抛，避免 cancel scope 抢占我们的重试异常）
        var openError: Throwable? = null
        val chunks = this@coroutineScope.produce<String>(capacity = 1) {
            // 增量 UTF-8 解码：跨 chunk 被拆开的多字节字符（中文/emoji）不再逐块 decodeToString 产生替换字符。
            val utf8 = logic.protocol.Utf8StreamDecoder()
            // Bedrock 之类非 SSE 帧：先由适配器的字节解码器还原成 SSE 文本再进后面的流水线
            val frames = upstreamAdapter.byteStreamDecoder()
            try {
                client.stream(request).collect { chunk ->
                    when (chunk) {
                        is HttpClientStreamChunk.Bytes -> {
                            val s = if (frames != null) frames.feed(chunk.bytes) else utf8.decode(chunk.bytes)
                            if (s.isNotEmpty()) send(s)
                        }
                        is HttpClientStreamChunk.Text -> { val s = if (frames != null) frames.feed(chunk.text.encodeToByteArray()) else chunk.text; if (s.isNotEmpty()) send(s) }
                        is HttpClientStreamChunk.Start, is HttpClientStreamChunk.End -> {}
                    }
                }
                val tail = if (frames != null) frames.finish() else utf8.flush(); if (tail.isNotEmpty()) send(tail)
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
                if (cause is HttpClientException) throw cause
                throw HttpClientException(HttpClientError.Network(cause.message ?: "stream open failed", cause as? Throwable))
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
                        val c = streamTransformer.feed(text)
                        // 签名状态引用必须先落库，客户端才可能看到它（见 ThoughtSignatureVault.flush）
                        signatureVault?.let { v -> runCatching { v.flush() }.onFailure { log.warn("signature vault flush failed: ${it.message}") } }
                        if (c.isNotEmpty()) writeChunk(c)
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
                    else -> streamTransformer?.finish()?.let {
                        signatureVault?.let { v -> runCatching { v.flush() }.onFailure { e -> log.warn("signature vault flush failed: ${e.message}") } }
                        if (it.isNotEmpty()) writeChunk(it)
                    }
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
                        bill(token, channel, rewritten, accumulator.usage(), group, groupSource, startMs, ttfbMs, status = "partial")
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
            bill(token, channel, rewritten, accumulator.usage(), group, groupSource, startMs, ttfbMs,
                 status = if (aborted) "partial" else if (accumulator.usage() != null) "ok" else "partial")
        }
        if (aborted) RelayOutcome.PARTIAL else RelayOutcome.SUCCESS
    }

    /** 转发变换：请求体/上游路径已终态；响应体/流按需转协议（同协议为恒等）。 */
    class RelayTransform(
        val rewritten: Rewritten,
        val upstream: ProtocolAdapter,
        val inbound: ProtocolAdapter,
        val upstreamPath: String,
        val transformResponseBody: (String) -> String,
        val streamTransformerFactory: (() -> StreamTransformer)?,
        /** 本次签发的签名状态引用，写给客户端之前必须 flush（见 [ThoughtSignatureVault.flush]）。 */
        val signatureVault: ThoughtSignatureVault? = null,
        /** 客户端协议（/v1/responses 时是 Responses，而不是 inbound 适配器的 Chat）。 */
        val inboundProtocol: String = inbound.id,
    ) {
        /**
         * 上游 4xx 的错误体：同协议原样透传；跨协议改写成客户端协议的错误格式。
         * 原样透传给 Anthropic SDK 一个 OpenAI 形状的 `{"error":{"message":…}}`，SDK 解析不出
         * `type` 就只剩一句 "unknown error"，用户看不到上游到底说了什么。三家的错误体都有
         * `error.message`，拿到它按 inbound 重新包一层，拿不到就把原文当 message。
         */
        fun errorBodyFor(upstreamBody: String, status: Int = 0): String {
            if (inbound.id == upstream.id) return upstreamBody
            // 两端格式都已知：由 neton-ai 按客户端协议重新渲染（保留错误类别）
            val from = ProtocolCodecs.formatOf(upstream.id)
            val to = ProtocolCodecs.formatOf(inboundProtocol)
            if (from != null && to != null) return neton.ai.protocol.convert.ProtocolConverter.convertError(status, upstreamBody, from, to).second
            val message = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(upstreamBody)
                    .jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: upstreamBody.take(500)
            return inbound.errorBody("upstream_error", message)
        }
    }

    /** 上游端点路径：openai 保留入站路径（含 embeddings），anthropic 固定 /v1/messages，
     *  gemini 用上游模型名 + action 重建。 */
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
        groupSource: GroupSource,
        startMs: Long,
        ttfbMs: Long?,
        /** null = 由 bill 自己判定（非流式 2xx 走这条）；流式调用方按 terminalSeen/aborted 显式给。 */
        status: String? = null,
    ) {
        val s = settlement ?: run {
            log.error("relay bill without reservation model=${rewritten.requestModel}（不应发生）")
            return
        }
        // V004 §7.1：必须按 reserve 时固化的定价快照计价——中途改价/改倍率不影响在途请求。
        // 快照无效（损坏/字段缺失/数值非法）= 数据事故：账务不允许猜价。禁止回退实时价——那会
        // 重新制造「旧价预留、新价扣款」；保留预留资金转人工复核，并打高优先级告警。
        val snapped = snapshotPrice(s.pricingSnapshot)
        if (snapped == null) {
            log.error("CRITICAL settlement pricing snapshot invalid sid=${s.settlementId} " +
                "model=${rewritten.requestModel} snapshot=${s.pricingSnapshot.take(200)}; " +
                "holding reservation, routing to MANUAL_REVIEW (no live-price fallback)")
            val r = settlementLogic.markManualReview(
                settlementId = s.settlementId, from = bySettlementStatus(s.settlementId),
                action = model.Settlement.ACTION_FINALIZE,
                error = "pricing snapshot invalid: ${s.pricingSnapshot.take(200)}",
            )
            if (r !is SettlementLogic.TransitionResult.Applied &&
                r !is SettlementLogic.TransitionResult.AlreadyDone
            ) log.error("settlement manual-review transition failed sid=${s.settlementId} r=$r; worker TTL will catch it")
            return
        }
        val effectiveUsage = usage ?: Usage()
        // 按次计价的模型（图片生成等）响应里本来就没有 usage：非流式 2xx + 完整 body 就是完整成功，
        // 不能因为「没拿到 usage」记成 partial —— 那会把每一张图都算成半截请求、Key 永远清不了零。
        // 只对没显式给 status 的调用（非流式）生效：流式被截断时调用方明确传 partial，按次计价也不能洗白。
        val perRequest = snapped.sale.perRequestPrice != null
        val outcome = status ?: if (usage != null || perRequest) "ok" else "partial"
        val terminal = usage != null || (status == null && perRequest)
        // 收入 = 售价轨 × 分组倍率；成本 = 官方价（成本轨）× 渠道折扣。四要素全部取自快照，
        // 结算时不重查任何定价表——改价、改折扣、改倍率只影响后续请求，不影响在途与重放。
        // 分档价格按本次实际的总输入 token 选档（售价轨与成本轨各自选）；单档价格原样
        val totalInput = PricingTiers.totalInput(effectiveUsage)
        val saleTier = PricingTiers.tierIndex(snapped.sale, totalInput)
        val charged = PricingCalc.charge(effectiveUsage, PricingTiers.select(snapped.sale, totalInput), snapped.ratio)
        val cost = PricingCalc.cost(effectiveUsage, PricingTiers.select(snapped.official, totalInput), snapped.costDiscount)
        // 毛利倒挂：发布闸门挡的是「配错价」，折扣 >1 的渠道与倍率 <1 的用户组仍可能压穿成本，必须可观测。
        if (cost > charged) {
            // 带上组与来源（V007）：同样是 ratio=3.0 倒挂，「谈判价配错了」与「标准价本身
            // 配错了」是两件完全不同的事，而此前这条告警只有倍率、区分不了。
            log.warn("margin inversion sid=${s.settlementId} model=${rewritten.requestModel} " +
                "group=$group/${groupSource.name} charged=$charged cost=$cost " +
                "ratio=${snapped.ratio} costDiscount=${snapped.costDiscount} tier=$saleTier")
        }
        if (saleTier > 0) log.info("relay tiered price applied sid=${s.settlementId} model=${rewritten.requestModel} tier=$saleTier totalInput=$totalInput")

        // T3：先把 usage payload 落库转入 durable 重试态。这一步失败且随后崩溃 = C3′（usage 永久丢失），
        // 故用 NonCancellable + 有界重试把概率压到最低；仍失败则留在 UPSTREAM_STARTED，由 worker TTL 转人工。
        val pending = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            var last: SettlementLogic.TransitionResult? = null
            repeat(3) { attempt ->
                last = runCatching {
                    settlementLogic.markFinalizePending(
                        settlementId = s.settlementId,
                        usageJson = usageJson(effectiveUsage),
                        charged = charged, cost = cost,
                        outcome = outcome, terminalSeen = terminal,
                        channelId = channel.id,
                        upstreamModel = rewritten.upstreamModel,
                        ttfbMs = ttfbMs, latencyMs = now() - startMs,
                        stream = rewritten.stream, usage = effectiveUsage,
                    )
                }.getOrElse { e ->
                    log.warn("settlement T3 attempt=${attempt + 1} failed sid=${s.settlementId}: ${e.message}")
                    null
                }
                if (last is SettlementLogic.TransitionResult.Applied ||
                    last is SettlementLogic.TransitionResult.AlreadyDone) return@withContext last
                kotlinx.coroutines.delay(50L * (attempt + 1))
            }
            last
        }
        if (pending !is SettlementLogic.TransitionResult.Applied &&
            pending !is SettlementLogic.TransitionResult.AlreadyDone) {
            log.error("settlement T3 exhausted sid=${s.settlementId}; reservation held for worker/manual (C3′)")
            return
        }

        // usage_log 由 finalize 在扣款同事务内写出（维度已随 T3 落在 settlement 行），此处不再单独记，避免重复计账

        // TPM 计入实际消耗（预检读的是上一分钟窗口的累计值）
        rateLimiter.recordTokens(token.id, effectiveUsage.promptTokens + effectiveUsage.completionTokens, now())

        // T4：释放预留 + 按实际扣款 + 台账（稳定幂等键 settlement:<sid>）+ quotaUsed，同一事务
        when (val r = settlementLogic.finalize(s.settlementId)) {
            is SettlementLogic.TransitionResult.Applied,
            is SettlementLogic.TransitionResult.AlreadyDone -> Unit
            else -> log.error("settlement finalize failed sid=${s.settlementId} r=$r; worker will retry")
        }
    }

    /**
     * 客户端 IP（用于令牌 IP 白名单）。
     *
     * 用框架统一解析的 [neton.core.http.HttpRequest.clientAddress]：从传输层对端出发，只有对端在
     * `routing.conf` 的 `ratelimit.trusted_proxies` 内才采信 `X-Forwarded-For`，从右往左剥掉可信跳。
     * 限流、审计、白名单用同一个值，不再各自解析转发头。解析不出（`unknown`）时按 null 处理，白名单拒绝。
     */
    private fun clientIp(ctx: HttpContext): String? =
        ctx.request.clientAddress.trim().takeIf { it.isNotEmpty() && it != "unknown" }

    /** 当前状态（RESERVED 或 UPSTREAM_STARTED）——释放时作为 CAS 的 from。 */
    private suspend fun bySettlementStatus(sid: String): String =
        settlementLogic.bySid(sid)?.status ?: model.Settlement.RESERVED

    private fun usageJson(u: Usage): String =
        """{"prompt":${u.promptTokens},"completion":${u.completionTokens},""" +
            """"cacheRead":${u.cacheReadTokens},"cacheWrite":${u.cacheWriteTokens}}"""

    /**
     * 双轨定价快照（V004 §7.1）：`sale` = 售价轨（收入），`cost` = 官方价（成本轨），
     * `ratio` = 分组倍率，`costDiscount` = 渠道折扣（选定/切换上游时并入，见 markUpstreamStarted）。
     * finalize 严格按它计价，不重查任何定价表。
     */
    /**
     * 成本按**实际打给上游的模型**算：渠道把 gpt-4o 映射成 gpt-4o-mini 时，我们付的是 mini 的钱。
     * 映射目标没有价格行（如 Azure 的部署名）就退回请求模型的官方价 —— 那通常就是同一个模型。
     */
    private suspend fun costBasisFor(rewritten: Rewritten, requestPrice: ModelPrice?): ModelPrice? {
        if (requestPrice == null || rewritten.upstreamModel == rewritten.requestModel) return requestPrice
        return ModelPriceTable.oneWhere { ModelPrice::model eq rewritten.upstreamModel } ?: requestPrice
    }

    private fun pricingSnapshot(
        sale: ModelPrice?, official: ModelPrice?, ratio: String, costDiscount: String? = null,
    ): String {
        if (sale == null || official == null) return "{}"
        val tail = if (costDiscount != null) ",\"costDiscount\":\"$costDiscount\"" else ""
        return """{"sale":${priceTrack(sale)},"ratio":"$ratio","cost":${priceTrack(official)}$tail}"""
    }

    private fun priceTrack(p: ModelPrice): String =
        """{"input":"${p.inputPrice}","output":"${p.outputPrice}","cacheRead":"${p.cacheReadPrice}",""" +
            """"cacheWrite":"${p.cacheWritePrice}","perRequest":${p.perRequestPrice},""" +
            """"tiers":${PricingTiers.normalize(p.tiers) ?: "null"}}"""

    /** [snapshotPrice] 解析出的双轨快照四要素；缺一即视为快照损坏。 */
    private data class SnapshotPricing(
        val sale: ModelPrice, val ratio: String, val official: ModelPrice, val costDiscount: String,
    )

    /**
     * 解析结算行存的定价快照（[pricingSnapshot] 的镜像，v2 双轨格式）。严格校验：JSON 非法、
     * 任一轨字段缺失、数值越界一律返回 null——绝不用默认值静默补齐，损坏快照宁可转人工也不按猜的价扣钱。
     */
    private fun snapshotPrice(json: String): SnapshotPricing? = runCatching {
        val o = Json.parseToJsonElement(json).jsonObject
        fun track(key: String): ModelPrice? {
            val t = o[key]?.jsonObject ?: return null
            fun field(k: String): String? = t[k]?.jsonPrimitive?.contentOrNull
                ?.takeIf { runCatching { PricingCalc.requirePrice(it) }.isSuccess }
            val input = field("input") ?: return null
            val output = field("output") ?: return null
            val cacheRead = field("cacheRead") ?: return null
            val cacheWrite = field("cacheWrite") ?: return null
            // perRequest：JSON null（非按次计价）或非负整数才合法；缺失/非法一律视为快照损坏
            val prPrim = t["perRequest"]?.jsonPrimitive ?: return null
            val perRequest = if (prPrim is JsonNull) null
                else prPrim.longOrNull?.takeIf { it >= 0 } ?: return null
            // tiers：旧快照没有这个键（单档）；有就必须是合法分档，否则视为快照损坏
            val tiers = t["tiers"]?.takeIf { it !is JsonNull }?.toString()
                ?.let { raw -> runCatching { PricingTiers.normalize(raw) }.getOrNull() ?: return null }
            return ModelPrice(
                model = "",
                inputPrice = input, outputPrice = output,
                cacheReadPrice = cacheRead, cacheWritePrice = cacheWrite,
                perRequestPrice = perRequest,
                tiers = tiers,
            )
        }
        fun ratioField(key: String): String? = o[key]?.jsonPrimitive?.contentOrNull
            ?.takeIf { runCatching { PricingCalc.requireRatio(it) }.isSuccess }
        val sale = track("sale") ?: return@runCatching null
        val cost = track("cost") ?: return@runCatching null
        val ratio = ratioField("ratio") ?: return@runCatching null
        val costDiscount = ratioField("costDiscount") ?: return@runCatching null
        SnapshotPricing(sale, ratio, cost, costDiscount)
    }.getOrNull()

    /**
     * 请求指纹（V004 §3.2）：**长度前缀编码**后 SHA-256。
     * 不用分隔符拼接——`a|b` 与 `a` + `|b` 会得到相同原文，导致不同请求碰撞同一指纹。
     */
    private fun requestFingerprint(ctx: HttpContext, path: String, rawBody: String): String {
        fun lp(s: String) = "${s.encodeToByteArray().size}:$s"
        val ct = ctx.request.header("Content-Type") ?: ""
        // 影响响应的协议头，排除认证载体与逐跳头
        val canonical = listOf("Accept", "Anthropic-Version", "Anthropic-Beta")
            .mapNotNull { h -> ctx.request.header(h)?.let { "$h=$it" } }
            .sorted().joinToString(",")
        return neton.security.digest.Sha256.hex(
            lp("POST") + lp(path) + lp(ct) + lp(canonical) + lp(rawBody)
        )
    }

    /** 识别下游断连（IO/传输层）异常：类名/消息含 IO/Closed/Broken/pipe/reset/Channel。 */
    private fun isDownstreamDisconnect(e: Throwable): Boolean {
        val n = (e::class.simpleName ?: "") + " " + (e.message ?: "")
        return listOf("IOException", "Closed", "Broken", "pipe", "reset", "ClosedWriteChannel", "ChannelWrite")
            .any { n.contains(it, ignoreCase = true) }
    }

    private suspend fun recordLog(
        token: GatewayToken, channel: Channel, rewritten: Rewritten, usage: Usage,
        group: String, groupSource: GroupSource, startMs: Long, status: String, errorCode: String?, ttfbMs: Long?,
        charged: Long = 0, cost: Long = 0,
    ): Long = UsageLogTable.insert(
        UsageLog(
            userId = token.userId, tokenId = token.id, channelId = channel.id,
            requestModel = rewritten.requestModel, upstreamModel = rewritten.upstreamModel,
            promptTokens = usage.promptTokens, completionTokens = usage.completionTokens,
            cacheReadTokens = usage.cacheReadTokens, cacheWriteTokens = usage.cacheWriteTokens,
            charged = charged, cost = cost,
            // group 参数从 V001 起就在签名里，却一直没被写进 UsageLog —— 表里没有那两列，
            // 而 Kotlin 不对未使用的函数参数报警告，于是它静默白传了很久（V007 补齐）。
            // 这条路径记的是上游语义错误（charged=0），归因照样要写：成功行由 finalize 落、
            // 失败行由这里落，两者进的是同一张表 —— 失败行缺归因，这张表就只能按组统计成功量，
            // 「某个组的客户是不是在集中撞某个上游」问不出来。
            groupCode = group, groupSource = groupSource.name,
            ttfbMs = ttfbMs, latencyMs = now() - startMs,
            stream = if (rewritten.stream) 1 else 0,
            status = status, errorCode = errorCode,
        )
    ).id

    private suspend fun respondError(ctx: HttpContext, inbound: ProtocolAdapter, status: Int, code: String, message: String) {
        respondRaw(ctx, status, inbound.errorBody(code, message))
    }

    private suspend fun respondRaw(ctx: HttpContext, status: Int, body: String, contentType: String? = null) {
        val mapped = HttpStatus.fromCode(status)
        if (mapped == null) {
            // 静默回落成 502 会把「余额不足(402)」这类语义错误伪装成上游故障——必须显式告警
            log.error("relay respond: unmapped HTTP status $status; falling back to 502 (add it to HttpStatus)")
        }
        ctx.response.status = mapped ?: HttpStatus.BAD_GATEWAY
        if (contentType != null) ctx.response.text(body, contentType) else ctx.response.json(body)
    }

    private fun now(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    companion object {
        const val RESPONSES_PATH = "/v1/responses"
        const val IMAGES_PATH = "/v1/images/generations"
        const val RERANK_PATH = "/v1/rerank"
        val AUDIO_PATHS = setOf("/v1/audio/speech", "/v1/audio/transcriptions", "/v1/audio/translations")
        val OPENAI_ONLY_PATHS = setOf(RESPONSES_PATH, IMAGES_PATH, RERANK_PATH) + AUDIO_PATHS
        /** 渠道类型 → 上游协议适配器；null = 未知类型（渠道配置错了，不当路由候选）。 */
        fun adapterForChannel(type: String): ProtocolAdapter? = when (type) {
            "openai_compatible" -> OpenAiAdapter
            "azure_openai" -> AzureOpenAiAdapter
            "anthropic" -> AnthropicAdapter
            "gemini" -> GeminiAdapter
            "bedrock" -> logic.protocol.aws.BedrockAdapter
            "bedrock_converse" -> logic.protocol.aws.BedrockConverseAdapter
            "vertex_anthropic" -> logic.protocol.vertex.VertexAnthropicAdapter
            "vertex_gemini" -> logic.protocol.vertex.VertexGeminiAdapter
            else -> null
        }

    fun upstreamPathFor(upstream: ProtocolAdapter, channel: Channel, inboundPath: String, upstreamModel: String, stream: Boolean): String =
        when {
            upstream === AzureOpenAiAdapter -> AzureOpenAiAdapter.path(inboundPath, upstreamModel, channel.apiVersion)
            upstream.upstreamPath(inboundPath, channel, upstreamModel, stream) != null -> upstream.upstreamPath(inboundPath, channel, upstreamModel, stream)!!
            upstream.id == "anthropic" -> "/v1/messages"
            upstream.id == "gemini" -> "/v1beta/models/$upstreamModel:" + if (stream) "streamGenerateContent?alt=sse" else "generateContent"
            else -> if (inboundPath.startsWith("/v1/")) inboundPath else "/v1/chat/completions"
        }

        /** JSON 解析用的哑渠道（rewrite 首过只取 model/stream）。 */
        private val DUMMY = Channel(id = 0, name = "", type = "", baseUrl = "")

        /**
         * 受控出站客户端的来源：由 [init.GatewayRuntimeBootstrap] 从 `NetonContext` 取（module-system 按
         * application.conf `[egress]` 绑定一次）。渠道的 baseUrl / proxyUrl、服务账号的 token_uri 都是后台
         * 运行期数据，所以渠道转发、探测、拉模型、能力校验、Vertex 换 token **全部**走它建的客户端：每条连接
         * 解析一次、全部地址按运维出站策略校验、只连校验过的地址；经代理时代理也只拿到校验过的 IP。
         * 写入时的 [NetGuard.checkUpstreamUrl] 只是快速反馈，这里才是强制点。
         */
        var guardedClients: GuardedHttpClientProvider? = null

        /** 价目表同步（地址来自部署环境变量 NETONAPI_PRICE_SYNC_URL）用的普通客户端来源；渠道流量绝不用它。 */
        var deployConfigClients: HttpClientProvider? = null

        /**
         * 渠道客户端缓存（[init.GatewayRuntimeBootstrap] 用 [guardedClients] 建、进程停止时关）。客户端只能经
         * [withChannelClient] 借用：渠道被改 / 删 / 停用时旧客户端退役，在用的请求归还后才关闭。
         */
        var channelClients: ChannelClientCache? = null

        /** [guarded] 建渠道客户端的缓存：渠道各自的代理与超时，一律受控（地址是后台运行期数据）。 */
        fun channelClientCache(guarded: GuardedHttpClientProvider): ChannelClientCache = ChannelClientCache(create = { channel ->
            guarded.create {
                connectMillis = 5_000
                requestMillis = 600_000               // 长流总上限 10 分钟
                socketMillis = channel.idleTimeoutMs  // 块间空闲超时
                proxyUrl = channel.proxyUrl
            }
        })

        /** 借 [channel] 的受控客户端执行 [block]（转发、探测、拉模型、能力校验）。 */
        suspend fun <T> withChannelClient(channel: Channel, block: suspend (HttpClient) -> T): T {
            val cache = channelClients ?: error(
                "gateway needs a GuardedHttpClientProvider bound in NetonContext (module-system binds it from [egress]).",
            )
            return cache.use(channel, block)
        }
    }
}
