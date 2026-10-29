package controller.admin.channel

import logic.ChannelCostListVO
import logic.ChannelCostSyncLogic
import logic.ChannelCostSyncResult
import logic.ManualCostRequest
import neton.core.annotations.*
import neton.core.interfaces.Identity

/**
 * 渠道实际成本价：列表（含待确认的异常变动）、立即从上游同步、手填 / 删除、接受 / 忽略待确认变动。
 * 见 netonapi-backend/docs/channel-cost-and-price-sync-design.md §4。
 */
@Controller("/gateway/channel-cost")
class ChannelCostController(private val sync: ChannelCostSyncLogic) {

    @Get("/list")
    @Permission("gateway:channel:query")
    suspend fun list(@Query channelId: Long): ChannelCostListVO = sync.list(channelId)

    @Post("/sync")
    @Permission("gateway:channel:update")
    suspend fun syncNow(@Query channelId: Long, identity: Identity): ChannelCostSyncResult =
        sync.syncChannel(channelId, identity.id.toLongOrNull())

    @Put("/manual")
    @Permission("gateway:channel:update")
    suspend fun manual(@Body request: ManualCostRequest, identity: Identity) = sync.saveManual(request, identity.id.toLongOrNull())

    @Delete("/delete")
    @Permission("gateway:channel:update")
    suspend fun delete(@Query channelId: Long, @Query model: String, identity: Identity) = sync.delete(channelId, model, identity.id.toLongOrNull())

    @Post("/pending/{id}/accept")
    @Permission("gateway:channel:update")
    suspend fun accept(@PathVariable id: Long, identity: Identity) = sync.acceptPending(id, identity.id.toLongOrNull())

    @Post("/pending/{id}/ignore")
    @Permission("gateway:channel:update")
    suspend fun ignore(@PathVariable id: Long, identity: Identity) = sync.ignorePending(id, identity.id.toLongOrNull())
}
