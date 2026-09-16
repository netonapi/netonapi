package controller.admin.gateway

import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.Permission
import neton.core.annotations.Query

@Controller("/gateway/info")
class GatewayInfoController(private val overviewLogic: logic.OverviewLogic) {
    @Get("/ping")
    suspend fun ping(): String = "gateway module ok"

    /** 概览统计；权限复用用量日志的（数字全部来自日志表，看得了明细就看得了汇总）。 */
    @Get("/overview")
    @Permission("gateway:log:list")
    suspend fun overview(@Query days: Int = 7): logic.OverviewVO = overviewLogic.overview(days)
}
