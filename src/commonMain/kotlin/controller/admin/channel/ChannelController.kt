package controller.admin.channel

import controller.admin.channel.dto.*
import logic.ChannelLogic
import model.Channel
import neton.core.annotations.*

@Controller("/gateway/channel")
class ChannelController(private val channelLogic: ChannelLogic) {

    @Get("/list")
    @Permission("gateway:channel:list")
    suspend fun list(): List<Channel> = channelLogic.list()

    @Get("/get/{id}")
    @Permission("gateway:channel:get")
    suspend fun get(@PathVariable id: Long): Channel? = channelLogic.get(id)

    @Post("/create")
    @Permission("gateway:channel:create")
    suspend fun create(@Body request: CreateChannelRequest): Long {
        val id = channelLogic.create(
            Channel(
                name = request.name, type = request.type, baseUrl = request.baseUrl,
                groups = request.groups, models = request.models,
                modelMapping = request.modelMapping, paramOverride = request.paramOverride,
                priority = request.priority, weight = request.weight,
                proxyUrl = request.proxyUrl, costDiscount = logic.PricingCalc.requireRatio(request.costDiscount, "costDiscount"),
                remark = request.remark,
            )
        )
        request.keys.forEach { channelLogic.addKey(id, it) }
        return id
    }

    @Put("/update")
    @Permission("gateway:channel:update")
    suspend fun update(@Body request: UpdateChannelRequest) {
        val current = channelLogic.get(request.id)
            ?: throw IllegalArgumentException("gateway channel id=${request.id} not found")
        channelLogic.update(current.copy(
            name = request.name, type = request.type, baseUrl = request.baseUrl,
            groups = request.groups, models = request.models,
            modelMapping = request.modelMapping, paramOverride = request.paramOverride,
            priority = request.priority, weight = request.weight,
            proxyUrl = request.proxyUrl, costDiscount = logic.PricingCalc.requireRatio(request.costDiscount, "costDiscount"),
            remark = request.remark,
        ))
    }

    @Put("/update-status")
    @Permission("gateway:channel:update")
    suspend fun updateStatus(@Query id: Long, @Query status: Int) =
        channelLogic.updateStatus(id, status)

    @Delete("/delete/{id}")
    @Permission("gateway:channel:delete")
    suspend fun delete(@PathVariable id: Long) = channelLogic.delete(id)

    @Get("/keys/{id}")
    @Permission("gateway:channel:get")
    suspend fun keys(@PathVariable id: Long): List<ChannelKeyVO> =
        channelLogic.listKeys(id).map {
            ChannelKeyVO(
                id = it.id,
                display = maskKey(it.apiKey),
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
}
