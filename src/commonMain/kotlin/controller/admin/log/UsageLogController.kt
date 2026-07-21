package controller.admin.log

import model.UsageLog
import neton.core.annotations.*
import neton.database.dsl.*
import table.UsageLogTable

@Controller("/gateway/log")
class UsageLogController {

    @Get("/list-by-user/{userId}")
    @Permission("gateway:log:list")
    suspend fun listByUser(@PathVariable userId: Long): List<UsageLog> =
        UsageLogTable.query { where { UsageLog::userId eq userId } }.list()

    @Get("/list-by-channel/{channelId}")
    @Permission("gateway:log:list")
    suspend fun listByChannel(@PathVariable channelId: Long): List<UsageLog> =
        UsageLogTable.query { where { UsageLog::channelId eq channelId } }.list()
}
