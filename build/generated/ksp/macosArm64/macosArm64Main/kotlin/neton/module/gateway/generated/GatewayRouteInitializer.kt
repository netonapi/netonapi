package neton.module.gateway.generated

import neton.core.interfaces.RequestEngine
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.RouteHandler
import neton.core.http.HttpContext
import neton.core.http.HandlerArgs
import neton.core.http.HttpMethod
import neton.core.http.ParamConverters
import neton.core.http.UnsupportedMediaTypeException
import neton.core.interfaces.Identity
import neton.core.interfaces.SecurityAttributes
import neton.core.http.UploadFile
import neton.core.http.UploadFiles
import neton.core.http.ValidationException
import neton.core.http.ValidationError
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer
import neton.core.http.JsonContent


/**
 * KSP 自动生成的路由初始化器
 *
 * 此文件由 Neton KSP 处理器自动生成，请勿手动编辑。
 * 包含 1 个控制器的路由注册。
 */
internal object GatewayRouteInitializer {

    /**
     * 初始化所有 KSP 生成的路由（从 ctx 获取 RequestEngine）
     * 签名必须与 neton-core 的 fallback 一致：initialize(ctx: NetonContext?)
     */
    fun initialize(ctx: neton.core.component.NetonContext?) {
        if (ctx == null) return
        val engine = ctx.get(neton.core.interfaces.RequestEngine::class)
        registerRoutes(engine, ctx)
    }

    /**
     * 注册所有路由到请求引擎
     */
    private fun registerRoutes(engine: RequestEngine, ctx: neton.core.component.NetonContext) {

        // === GatewayInfoController 控制器路由 ===

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/info/ping",
                method = HttpMethod.GET,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.gateway.GatewayInfoController()
                        
                        val _r = ctrl.ping()
                        return JsonContent(Json.encodeToString(String.serializer(), _r))

                    }
                },
                controllerClass = "controller.admin.gateway.GatewayInfoController",
                methodName = "ping",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = null,
                rateLimit = null,
                freshAuth = false
            )
        )

    }

    /**
     * 获取总路由数量
     */
    private fun getTotalRoutes(): Int {
        return 1
    }

    /**
     * 获取控制器统计信息
     */
    fun getControllerStats(): String {
        return "Controllers: 1, Routes: ${getTotalRoutes()}"
    }
}
