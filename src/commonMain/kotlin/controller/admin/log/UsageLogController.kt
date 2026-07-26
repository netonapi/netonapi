package controller.admin.log

import dto.PageResponse
import model.UsageLog
import neton.core.annotations.*
import neton.database.dsl.*
import table.UsageLogTable

@Controller("/gateway/log")
class UsageLogController {

    /**
     * 分页查询用量日志（管理台日志页的实际入口）。
     * 此前只有两个不分页的 list-by-*，日志页请求的 `/page` 根本不存在；
     * 用量表会持续增长，全量返回既拖垮接口也无法运营。
     */
    @Get("/page")
    @Permission("gateway:log:list")
    suspend fun page(
        @Query pageNo: Int = 1,
        @Query pageSize: Int = 20,
        @Query userId: Long? = null,
        @Query channelId: Long? = null,
        @Query requestModel: String? = null,
        @Query status: String? = null,
    ): PageResponse<UsageLog> {
        val size = pageSize.coerceIn(1, 200)
        val no = pageNo.coerceAtLeast(1)
        val result = UsageLogTable.query {
            where {
                and(
                    whenPresent(userId) { UsageLog::userId eq it },
                    whenPresent(channelId) { UsageLog::channelId eq it },
                    whenPresent(requestModel?.takeIf { it.isNotBlank() }) { UsageLog::requestModel eq it },
                    whenPresent(status?.takeIf { it.isNotBlank() }) { UsageLog::status eq it },
                )
            }
            orderBy(UsageLog::id.desc())
        }.page(no, size)
        return PageResponse(result.items, result.total, no, size, result.totalPages)
    }

    @Get("/list-by-user/{userId}")
    @Permission("gateway:log:list")
    suspend fun listByUser(@PathVariable userId: Long): List<UsageLog> =
        UsageLogTable.query { where { UsageLog::userId eq userId } }.list()

    @Get("/list-by-channel/{channelId}")
    @Permission("gateway:log:list")
    suspend fun listByChannel(@PathVariable channelId: Long): List<UsageLog> =
        UsageLogTable.query { where { UsageLog::channelId eq channelId } }.list()
}
