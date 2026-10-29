package controller.admin.channel

import logic.GatewayErrorCodes
import logic.rejectField
import neton.core.http.HttpException
import neton.core.http.NetonErrorCode
import controller.admin.channel.dto.*
import logic.ChannelLogic
import model.Channel
import neton.core.annotations.*
import neton.logging.Logger

@Controller("/gateway/channel")
class ChannelController(
    private val log: Logger,
    private val channelLogic: ChannelLogic,
    private val probeLogic: logic.ChannelProbeLogic,
) {

    @Get("/list")
    @Permission("gateway:channel:query")
    suspend fun list(): List<Channel> = channelLogic.list()

    @Get("/get/{id}")
    @Permission("gateway:channel:query")
    suspend fun get(@PathVariable id: Long): Channel? = channelLogic.get(id)

    @Post("/create")
    @Permission("gateway:channel:create")
    suspend fun create(@Body request: CreateChannelRequest): Long {
        requireSafeUrls(request.baseUrl, request.proxyUrl)
        request.upstreamPricingUrl?.takeIf { it.isNotBlank() }?.let { requireSafeUrls(it, null) }
        request.keys.forEach { requireSafeKey(it) }
        val id = channelLogic.create(
            Channel(
                name = request.name, type = request.type, baseUrl = request.baseUrl,
                groups = request.groups, models = request.models,
                modelMapping = request.modelMapping, paramOverride = request.paramOverride,
                priority = request.priority, weight = request.weight,
                capabilities = logic.RelayRouter.requireCapabilities(request.capabilities),
                proxyUrl = logic.effectiveProxyUrl(request.proxyUrl), apiVersion = request.apiVersion?.trim()?.takeIf { it.isNotEmpty() }, providerConfig = request.providerConfig?.trim()?.takeIf { it.isNotEmpty() },
                lossPolicy = logic.ConversionPolicy.requirePolicy(request.lossPolicy), reasoningSuffix = if (request.reasoningSuffix == 1) 1 else 0,
                remark = request.remark,
                status = if (request.status == 0) 0 else 1,
            ).let {
                logic.ChannelCostLogic.resolveCostConfig(
                    logic.ChannelCostLogic.CostInput(request.costSource, request.costDiscount, request.upstreamPricingFormat,
                        request.upstreamPricingUrl, request.upstreamGroupRatio, request.upstreamCreditRate),
                    it, current = null,
                )
            }
        )
        request.keys.forEach { channelLogic.addKey(id, it) }
        return id
    }

    @Put("/update")
    @Permission("gateway:channel:update")
    suspend fun update(@Body request: UpdateChannelRequest) {
        requireSafeUrls(request.baseUrl, request.proxyUrl)
        request.upstreamPricingUrl?.takeIf { it.isNotBlank() }?.let { requireSafeUrls(it, null) }
        val current = channelLogic.get(request.id)
            ?: throw HttpException(NetonErrorCode.RESOURCE_NOT_FOUND, GatewayErrorCodes.CHANNEL_NOT_FOUND)
        channelLogic.update(current.copy(
            name = request.name, type = request.type, baseUrl = request.baseUrl,
            groups = request.groups, models = request.models,
            modelMapping = request.modelMapping, paramOverride = request.paramOverride,
            priority = request.priority, weight = request.weight,
            capabilities = logic.RelayRouter.requireCapabilities(request.capabilities),
            proxyUrl = logic.effectiveProxyUrl(request.proxyUrl), apiVersion = request.apiVersion?.trim()?.takeIf { it.isNotEmpty() }, providerConfig = request.providerConfig?.trim()?.takeIf { it.isNotEmpty() },
            lossPolicy = logic.ConversionPolicy.requirePolicy(request.lossPolicy), reasoningSuffix = if (request.reasoningSuffix == 1) 1 else 0,
            remark = request.remark,
        ).let {
            logic.ChannelCostLogic.resolveCostConfig(
                logic.ChannelCostLogic.CostInput(request.costSource, request.costDiscount, request.upstreamPricingFormat,
                    request.upstreamPricingUrl, request.upstreamGroupRatio, request.upstreamCreditRate),
                it, current = current,
            )
        })
        request.status?.let { if (it != current.status) channelLogic.updateStatus(request.id, it) }
    }

    @Put("/update-status")
    @Permission("gateway:channel:update")
    suspend fun updateStatus(@Query id: Long, @Query status: Int) =
        channelLogic.updateStatus(id, status)

    /** 手动恢复自动禁用的渠道：渠道启用 + 全部 Key 复位。 */
    @Put("/revive/{id}")
    @Permission("gateway:channel:update")
    suspend fun revive(@PathVariable id: Long) = channelLogic.reviveChannel(id)

    /** 连通性探测：按渠道自己的协议/代理/Key 打一条最小请求，不计费不记日志不动 Key 状态。 */
    @Post("/probe/{id}")
    @Permission("gateway:channel:update")
    suspend fun probe(@PathVariable id: Long, @Body request: ProbeChannelRequest): logic.ChannelProbeResult {
        val channel = channelLogic.get(id)
            ?: throw HttpException(NetonErrorCode.RESOURCE_NOT_FOUND, GatewayErrorCodes.CHANNEL_NOT_FOUND)
        return probeLogic.probeAndRecord(channel, request.model)
    }

    /**
     * 拉取上游模型清单，记进渠道的「支持的模型」（supported_models）。只是给模型挑上游时的候选：
     * 不并进服务清单 models，上游支持什么不让任何模型对外（docs/model-catalog-design.md）。
     */
    @Post("/models/{id}")
    @Permission("gateway:channel:update")
    suspend fun upstreamModels(@PathVariable id: Long): logic.ChannelModelsResult {
        val channel = channelLogic.get(id)
            ?: throw HttpException(NetonErrorCode.RESOURCE_NOT_FOUND, GatewayErrorCodes.CHANNEL_NOT_FOUND)
        val result = probeLogic.listModels(channel)
        if (result.ok) channelLogic.get(id)?.let { channelLogic.update(it.copy(supportedModels = result.models.joinToString(","))) }
        return result
    }

    /** 某个模型的服务上游：每个渠道是否支持、是否正在服务、上游侧模型名。 */
    @Get("/model-routes")
    @Permission("gateway:channel:query")
    suspend fun modelRoutes(@Query model: String): List<ModelRouteVO> {
        val m = model.trim()
        return channelLogic.list().map { ch ->
            val r = logic.ModelRoutes.routeOf(ch, m)
            ModelRouteVO(
                channelId = ch.id, name = ch.name, type = ch.type, status = ch.status, priority = ch.priority,
                supported = r.supported, serving = r.serving, upstreamModel = r.upstreamModel,
            )
        }.sortedWith(compareByDescending<ModelRouteVO> { it.serving }.thenByDescending { it.supported }.thenByDescending { it.priority })
    }

    /**
     * 设定某个模型由哪些渠道服务（写回各渠道的服务清单与模型映射）。改的是渠道，要渠道的更新权限：
     * 财务能改价、不能改线路（与渠道本身同一道权限）。
     */
    @Put("/model-routes")
    @Permission("gateway:channel:update")
    suspend fun saveModelRoutes(@Body request: SaveModelRoutesRequest) {
        val m = request.model.trim()
        if (m.isEmpty()) rejectField("model", GatewayErrorCodes.MODEL_REQUIRED)
        val all = channelLogic.list()
        val known = all.map { it.id }.toSet()
        val selected = request.routes.filter { it.channelId in known }.associate { it.channelId to it.upstreamModel }
        logic.ModelRoutes.apply(all, m, selected).forEach { channelLogic.update(it) }
    }

    @Delete("/delete/{id}")
    @Permission("gateway:channel:delete")
    suspend fun delete(@PathVariable id: Long) = channelLogic.delete(id)

    @Get("/keys/{id}")
    @Permission("gateway:channel:query")
    suspend fun keys(@PathVariable id: Long): List<ChannelKeyVO> =
        channelLogic.listKeys(id).map {
            ChannelKeyVO(
                id = it.id,
                // 先解密再掩码——直接掩码密文只会显示密文片段；只有掩码离开进程
                display = maskKey(logic.GatewayCrypto.open(it.apiKey)),
                status = it.status, failCount = it.failCount, lastError = it.lastError,
            )
        }

    @Post("/keys/{id}/add")
    @Permission("gateway:channel:update")
    suspend fun addKey(@PathVariable id: Long, @Body request: AddKeyRequest): Long {
        requireSafeKey(request.apiKey)
        return channelLogic.addKey(id, request.apiKey)
    }

    @Delete("/keys/delete/{keyId}")
    @Permission("gateway:channel:update")
    suspend fun deleteKey(@PathVariable keyId: Long) = channelLogic.deleteKey(keyId)

    private fun maskKey(key: String): String =
        if (key.length <= 11) "****" else key.take(7) + "****" + key.takeLast(4)

    /**
     * 上游地址的写入时校验（快速反馈）：与请求时的受控客户端用**同一个**运维出站策略（application.conf
     * `[egress]`）和同一个解析器，拒绝指向回环 / 内网 / 链路本地（含云元数据 169.254.169.254）、端口不在放行
     * 列表、解析不出的地址；代理地址按代理规则（地址，端口不限）。强制点在请求时（[logic.RelayEngine.withChannelClient]），
     * 写库绕过本接口的渠道、之后改了 DNS 的域名一样被挡在连接前。自托管连内网上游由运维在部署配置里放行。
     */
    private suspend fun requireSafeUrls(baseUrl: String, proxyUrl: String?) {
        checkField("baseUrl", baseUrl, logic.NetGuard.UrlRole.UPSTREAM)
        if (!proxyUrl.isNullOrBlank()) checkField("proxyUrl", proxyUrl, logic.NetGuard.UrlRole.PROXY)
    }

    /**
     * 服务账号 JSON 形态的 Key（Vertex）里带 token_uri：换 token 时网关会把签名断言 POST 过去，所以它和 baseUrl
     * 一样是要校验的出站地址（只允许 https）。普通字符串 Key 不含地址，直接放过。
     */
    private suspend fun requireSafeKey(apiKey: String) {
        val trimmed = apiKey.trim()
        if (!trimmed.startsWith("{")) return
        val sa = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(trimmed) as kotlinx.serialization.json.JsonObject }
            .getOrElse { rejectField("apiKey", GatewayErrorCodes.CHANNEL_KEY_SERVICE_ACCOUNT_INVALID) }
        val tokenUri = (sa["token_uri"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: return // 缺省 Google 的 token 端点
        checkField("apiKey", tokenUri, logic.NetGuard.UrlRole.TOKEN_ENDPOINT)
    }

    private suspend fun checkField(field: String, url: String, role: logic.NetGuard.UrlRole) {
        // GatewayRuntimeBootstrap 取不到就启动失败，服务期必然存在。
        val guarded = checkNotNull(logic.RelayEngine.guardedClients) { "GuardedHttpClientProvider not bound" }
        val v = logic.NetGuard.checkUpstreamUrl(url, guarded.policy, neton.http.client.systemHostResolver(), role)
        if (v is logic.NetGuard.UrlVerdict.Rejected) {
            log.warn("channel SSRF check rejected field=$field code=${v.code} reason=${v.reason}")
            rejectField(field, v.code) // v.code 恒为 GatewayErrorCodes.UPSTREAM_*
        }
    }
}
