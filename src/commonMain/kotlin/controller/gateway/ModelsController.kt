package controller.gateway

import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.http.HttpResponse

/**
 * OpenAI 兼容 /v1/models。gateway 组裸响应（不走 envelope）：直接 response.json。
 * M1b 接入定价/渠道后按令牌可用模型过滤，这里先返回空表打通链路。
 */
@Controller("/v1/models")
class ModelsController {
    @Get("")
    suspend fun list(response: HttpResponse) {
        response.json("""{"object":"list","data":[]}""")
    }
}
