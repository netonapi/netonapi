package controller.admin.gateway

import neton.core.annotations.Controller
import neton.core.annotations.Get

@Controller("/gateway/info")
class GatewayInfoController {
    @Get("/ping")
    suspend fun ping(): String = "gateway module ok"
}
