package controller.admin.channel

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
    @Permission("gateway:channel:list")
    suspend fun list(): List<Channel> = channelLogic.list()

    @Get("/get/{id}")
    @Permission("gateway:channel:get")
    suspend fun get(@PathVariable id: Long): Channel? = channelLogic.get(id)

    @Post("/create")
    @Permission("gateway:channel:create")
    suspend fun create(@Body request: CreateChannelRequest): Long {
        requireSafeUrls(request.baseUrl, request.proxyUrl)
        val id = channelLogic.create(
            Channel(
                name = request.name, type = request.type, baseUrl = request.baseUrl,
                groups = request.groups, models = request.models,
                modelMapping = request.modelMapping, paramOverride = request.paramOverride,
                priority = request.priority, weight = request.weight,
                capabilities = logic.RelayRouter.requireCapabilities(request.capabilities),
                proxyUrl = request.proxyUrl, apiVersion = request.apiVersion?.trim()?.takeIf { it.isNotEmpty() }, costDiscount = logic.PricingCalc.requireRatio(request.costDiscount, "costDiscount"),
                remark = request.remark,
            )
        )
        request.keys.forEach { channelLogic.addKey(id, it) }
        return id
    }

    @Put("/update")
    @Permission("gateway:channel:update")
    suspend fun update(@Body request: UpdateChannelRequest) {
        requireSafeUrls(request.baseUrl, request.proxyUrl)
        val current = channelLogic.get(request.id)
            ?: throw IllegalArgumentException("gateway channel id=${request.id} not found")
        channelLogic.update(current.copy(
            name = request.name, type = request.type, baseUrl = request.baseUrl,
            groups = request.groups, models = request.models,
            modelMapping = request.modelMapping, paramOverride = request.paramOverride,
            priority = request.priority, weight = request.weight,
            capabilities = logic.RelayRouter.requireCapabilities(request.capabilities),
            proxyUrl = request.proxyUrl, apiVersion = request.apiVersion?.trim()?.takeIf { it.isNotEmpty() }, costDiscount = logic.PricingCalc.requireRatio(request.costDiscount, "costDiscount"),
            remark = request.remark,
        ))
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
        val channel = channelLogic.get(id) ?: throw IllegalArgumentException("gateway channel id=$id not found")
        return probeLogic.probeAndRecord(channel, request.model)
    }

    /** 拉取上游模型清单（不写库；由前端决定是否并入渠道的 models）。 */
    @Post("/models/{id}")
    @Permission("gateway:channel:update")
    suspend fun upstreamModels(@PathVariable id: Long): logic.ChannelModelsResult {
        val channel = channelLogic.get(id) ?: throw IllegalArgumentException("gateway channel id=$id not found")
        return probeLogic.listModels(channel)
    }

    @Delete("/delete/{id}")
    @Permission("gateway:channel:delete")
    suspend fun delete(@PathVariable id: Long) = channelLogic.delete(id)

    @Get("/keys/{id}")
    @Permission("gateway:channel:get")
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
    suspend fun addKey(@PathVariable id: Long, @Body request: AddKeyRequest): Long =
        channelLogic.addKey(id, request.apiKey)

    @Delete("/keys/delete/{keyId}")
    @Permission("gateway:channel:update")
    suspend fun deleteKey(@PathVariable keyId: Long) = channelLogic.deleteKey(keyId)

    private fun maskKey(key: String): String =
        if (key.length <= 11) "****" else key.take(7) + "****" + key.takeLast(4)

    /**
     * 上游地址 SSRF 校验：拒绝指向回环/内网/链路本地（含云元数据 169.254.169.254）的地址，
     * 否则网关会成为打内网的跳板。域名还要解析一次复查——字面量看不出 `http://upstream.example`
     * 指向哪里。自托管需要连内网上游时，设 NEWGATE_ALLOW_PRIVATE_UPSTREAM=true 显式放行。
     */
    private fun requireSafeUrls(baseUrl: String, proxyUrl: String?) {
        val allowPrivate = neton.core.config.getEnv("NEWGATE_ALLOW_PRIVATE_UPSTREAM")?.lowercase() == "true"
        val resolver = logic.platformHostResolver()
        if (resolver == null) {
            // 本平台没有 DNS 解析器：只剩字面量校验。明确说出来，别让人以为域名也被查过了。
            log.warn("channel SSRF check: DNS re-check unavailable on this platform, only literal hosts are validated")
        }
        for ((field, url) in listOf("baseUrl" to baseUrl, "proxyUrl" to proxyUrl)) {
            if (url.isNullOrBlank()) continue
            val v = logic.NetGuard.checkUpstreamUrl(url, allowPrivate, resolver)
            if (v is logic.NetGuard.UrlVerdict.Rejected) {
                throw neton.core.http.ValidationException(
                    listOf(neton.core.http.ValidationError(path = field, message = v.reason))
                )
            }
        }
    }
}
