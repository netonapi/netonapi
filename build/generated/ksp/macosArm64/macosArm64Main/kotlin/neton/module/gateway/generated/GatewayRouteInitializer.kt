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
import neton.validation.ValidatorRegistry
import controller.admin.channel.dto.CreateChannelRequest
import controller.admin.channel.dto.UpdateChannelRequest
import controller.admin.channel.dto.AddKeyRequest
import neton.core.http.JsonContent
import model.Channel
import controller.admin.channel.dto.ChannelKeyVO
import kotlinx.serialization.builtins.nullable


/**
 * KSP 自动生成的路由初始化器
 *
 * 此文件由 Neton KSP 处理器自动生成，请勿手动编辑。
 * 包含 2 个控制器的路由注册。
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

        // === ChannelController 控制器路由 ===

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/channel/list",
                method = HttpMethod.GET,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.channel.ChannelController(ctx.get(logic.ChannelLogic::class))
                        
                        val _r = ctrl.list()
                        return JsonContent(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Channel.serializer()), _r))

                    }
                },
                controllerClass = "controller.admin.channel.ChannelController",
                methodName = "list",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:channel:list",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/channel/get/{id}",
                method = HttpMethod.GET,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.channel.ChannelController(ctx.get(logic.ChannelLogic::class))
                        
                        val _r = ctrl.get(run { val _r = args.first("id") as? String; if (_r == null) throw ValidationException(listOf(ValidationError(path = "id", message = "is required", code = "Missing"))); ParamConverters.parseLong(_r) ?: throw ValidationException(listOf(ValidationError(path = "id", message = "must be a valid integer", code = "Type"))) })
                        return JsonContent(Json.encodeToString(Channel.serializer().nullable, _r))

                    }
                },
                controllerClass = "controller.admin.channel.ChannelController",
                methodName = "get",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:channel:get",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/channel/create",
                method = HttpMethod.POST,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.channel.ChannelController(ctx.get(logic.ChannelLogic::class))
                        
                        val _r = ctrl.create(run {
                                val ct = context.request.contentType?.lowercase() ?: ""
                                if (!ct.contains("application/json")) throw UnsupportedMediaTypeException("Body requires application/json")
                                val raw = context.request.text()
                                val body = try {
                                    Json.decodeFromString(CreateChannelRequest.serializer(), raw)
                                } catch (e: Exception) {
                                    throw ValidationException(listOf(ValidationError(path = "\$", message = "Invalid JSON body", code = "InvalidJson")))
                                }
                                val registry = context.getApplicationContext()?.getOrNull(neton.validation.ValidatorRegistry::class)
                                val validator = registry?.get(CreateChannelRequest::class)
                                val errors = validator?.validate(body)
                                if (!errors.isNullOrEmpty()) throw ValidationException(errors)
                                body
                            })
                        return JsonContent(Json.encodeToString(Long.serializer(), _r))

                    }
                },
                controllerClass = "controller.admin.channel.ChannelController",
                methodName = "create",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:channel:create",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/channel/update",
                method = HttpMethod.PUT,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.channel.ChannelController(ctx.get(logic.ChannelLogic::class))
                        
                        return ctrl.update(run {
                                val ct = context.request.contentType?.lowercase() ?: ""
                                if (!ct.contains("application/json")) throw UnsupportedMediaTypeException("Body requires application/json")
                                val raw = context.request.text()
                                val body = try {
                                    Json.decodeFromString(UpdateChannelRequest.serializer(), raw)
                                } catch (e: Exception) {
                                    throw ValidationException(listOf(ValidationError(path = "\$", message = "Invalid JSON body", code = "InvalidJson")))
                                }
                                val registry = context.getApplicationContext()?.getOrNull(neton.validation.ValidatorRegistry::class)
                                val validator = registry?.get(UpdateChannelRequest::class)
                                val errors = validator?.validate(body)
                                if (!errors.isNullOrEmpty()) throw ValidationException(errors)
                                body
                            })

                    }
                },
                controllerClass = "controller.admin.channel.ChannelController",
                methodName = "update",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:channel:update",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/channel/update-status",
                method = HttpMethod.PUT,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.channel.ChannelController(ctx.get(logic.ChannelLogic::class))
                        
                        return ctrl.updateStatus(run { val _r = args.first("id") as? String; if (_r == null) throw ValidationException(listOf(ValidationError(path = "id", message = "is required", code = "Missing"))); ParamConverters.parseLong(_r) ?: throw ValidationException(listOf(ValidationError(path = "id", message = "must be a valid integer", code = "Type"))) }, run { val _r = args.first("status") as? String; if (_r == null) throw ValidationException(listOf(ValidationError(path = "status", message = "is required", code = "Missing"))); ParamConverters.parseInt(_r) ?: throw ValidationException(listOf(ValidationError(path = "status", message = "must be a valid integer", code = "Type"))) })

                    }
                },
                controllerClass = "controller.admin.channel.ChannelController",
                methodName = "updateStatus",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:channel:update",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/channel/delete/{id}",
                method = HttpMethod.DELETE,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.channel.ChannelController(ctx.get(logic.ChannelLogic::class))
                        
                        return ctrl.delete(run { val _r = args.first("id") as? String; if (_r == null) throw ValidationException(listOf(ValidationError(path = "id", message = "is required", code = "Missing"))); ParamConverters.parseLong(_r) ?: throw ValidationException(listOf(ValidationError(path = "id", message = "must be a valid integer", code = "Type"))) })

                    }
                },
                controllerClass = "controller.admin.channel.ChannelController",
                methodName = "delete",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:channel:delete",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/channel/keys/{id}",
                method = HttpMethod.GET,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.channel.ChannelController(ctx.get(logic.ChannelLogic::class))
                        
                        val _r = ctrl.keys(run { val _r = args.first("id") as? String; if (_r == null) throw ValidationException(listOf(ValidationError(path = "id", message = "is required", code = "Missing"))); ParamConverters.parseLong(_r) ?: throw ValidationException(listOf(ValidationError(path = "id", message = "must be a valid integer", code = "Type"))) })
                        return JsonContent(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ChannelKeyVO.serializer()), _r))

                    }
                },
                controllerClass = "controller.admin.channel.ChannelController",
                methodName = "keys",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:channel:get",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/channel/keys/{id}/add",
                method = HttpMethod.POST,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.channel.ChannelController(ctx.get(logic.ChannelLogic::class))
                        
                        val _r = ctrl.addKey(run { val _r = args.first("id") as? String; if (_r == null) throw ValidationException(listOf(ValidationError(path = "id", message = "is required", code = "Missing"))); ParamConverters.parseLong(_r) ?: throw ValidationException(listOf(ValidationError(path = "id", message = "must be a valid integer", code = "Type"))) }, run {
                                val ct = context.request.contentType?.lowercase() ?: ""
                                if (!ct.contains("application/json")) throw UnsupportedMediaTypeException("Body requires application/json")
                                val raw = context.request.text()
                                val body = try {
                                    Json.decodeFromString(AddKeyRequest.serializer(), raw)
                                } catch (e: Exception) {
                                    throw ValidationException(listOf(ValidationError(path = "\$", message = "Invalid JSON body", code = "InvalidJson")))
                                }
                                val registry = context.getApplicationContext()?.getOrNull(neton.validation.ValidatorRegistry::class)
                                val validator = registry?.get(AddKeyRequest::class)
                                val errors = validator?.validate(body)
                                if (!errors.isNullOrEmpty()) throw ValidationException(errors)
                                body
                            })
                        return JsonContent(Json.encodeToString(Long.serializer(), _r))

                    }
                },
                controllerClass = "controller.admin.channel.ChannelController",
                methodName = "addKey",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:channel:update",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/channel/keys/delete/{keyId}",
                method = HttpMethod.DELETE,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.channel.ChannelController(ctx.get(logic.ChannelLogic::class))
                        
                        return ctrl.deleteKey(run { val _r = args.first("keyId") as? String; if (_r == null) throw ValidationException(listOf(ValidationError(path = "keyId", message = "is required", code = "Missing"))); ParamConverters.parseLong(_r) ?: throw ValidationException(listOf(ValidationError(path = "keyId", message = "must be a valid integer", code = "Type"))) })

                    }
                },
                controllerClass = "controller.admin.channel.ChannelController",
                methodName = "deleteKey",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:channel:update",
                rateLimit = null,
                freshAuth = false
            )
        )

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
        return 10
    }

    /**
     * 获取控制器统计信息
     */
    fun getControllerStats(): String {
        return "Controllers: 2, Routes: ${getTotalRoutes()}"
    }
}
