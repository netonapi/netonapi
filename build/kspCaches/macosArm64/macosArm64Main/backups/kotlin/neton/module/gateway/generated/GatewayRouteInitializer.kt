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
import neton.logging.LoggerFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer
import neton.validation.ValidatorRegistry
import controller.admin.token.CreateTokenRequest
import controller.admin.group.SaveGroupRequest
import controller.admin.channel.dto.CreateChannelRequest
import controller.admin.channel.dto.UpdateChannelRequest
import controller.admin.channel.dto.AddKeyRequest
import controller.admin.price.SavePriceRequest
import controller.admin.quota.GrantQuotaRequest
import neton.core.http.JsonContent
import controller.admin.token.IssuedTokenVO
import controller.admin.token.TokenVO
import model.GatewayGroup
import model.Channel
import controller.admin.channel.dto.ChannelKeyVO
import model.ModelPrice
import model.UsageLog
import model.QuotaAccount
import model.QuotaTransaction
import kotlinx.serialization.builtins.nullable


/**
 * KSP 自动生成的路由初始化器
 *
 * 此文件由 Neton KSP 处理器自动生成，请勿手动编辑。
 * 包含 10 个控制器的路由注册。
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

        // === TokenController 控制器路由 ===

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/token/create",
                method = HttpMethod.POST,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.token.TokenController(ctx.get(logic.TokenLogic::class))
                        
                        val _r = ctrl.create(run {
                                val ct = context.request.contentType?.lowercase() ?: ""
                                if (!ct.contains("application/json")) throw UnsupportedMediaTypeException("Body requires application/json")
                                val raw = context.request.text()
                                val body = try {
                                    Json.decodeFromString(CreateTokenRequest.serializer(), raw)
                                } catch (e: Exception) {
                                    throw ValidationException(listOf(ValidationError(path = "\$", message = "Invalid JSON body", code = "InvalidJson")))
                                }
                                val registry = context.getApplicationContext()?.getOrNull(neton.validation.ValidatorRegistry::class)
                                val validator = registry?.get(CreateTokenRequest::class)
                                val errors = validator?.validate(body)
                                if (!errors.isNullOrEmpty()) throw ValidationException(errors)
                                body
                            })
                        return JsonContent(Json.encodeToString(IssuedTokenVO.serializer(), _r))

                    }
                },
                controllerClass = "controller.admin.token.TokenController",
                methodName = "create",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:token:create",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/token/list-by-user/{userId}",
                method = HttpMethod.GET,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.token.TokenController(ctx.get(logic.TokenLogic::class))
                        
                        val _r = ctrl.listByUser(run { val _r = args.first("userId") as? String; if (_r == null) throw ValidationException(listOf(ValidationError(path = "userId", message = "is required", code = "Missing"))); ParamConverters.parseLong(_r) ?: throw ValidationException(listOf(ValidationError(path = "userId", message = "must be a valid integer", code = "Type"))) })
                        return JsonContent(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(TokenVO.serializer()), _r))

                    }
                },
                controllerClass = "controller.admin.token.TokenController",
                methodName = "listByUser",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:token:list",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/token/update-status",
                method = HttpMethod.PUT,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.token.TokenController(ctx.get(logic.TokenLogic::class))
                        
                        return ctrl.updateStatus(run { val _r = args.first("id") as? String; if (_r == null) throw ValidationException(listOf(ValidationError(path = "id", message = "is required", code = "Missing"))); ParamConverters.parseLong(_r) ?: throw ValidationException(listOf(ValidationError(path = "id", message = "must be a valid integer", code = "Type"))) }, run { val _r = args.first("status") as? String; if (_r == null) throw ValidationException(listOf(ValidationError(path = "status", message = "is required", code = "Missing"))); ParamConverters.parseInt(_r) ?: throw ValidationException(listOf(ValidationError(path = "status", message = "must be a valid integer", code = "Type"))) })

                    }
                },
                controllerClass = "controller.admin.token.TokenController",
                methodName = "updateStatus",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:token:update",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/token/delete/{id}",
                method = HttpMethod.DELETE,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.token.TokenController(ctx.get(logic.TokenLogic::class))
                        
                        return ctrl.delete(run { val _r = args.first("id") as? String; if (_r == null) throw ValidationException(listOf(ValidationError(path = "id", message = "is required", code = "Missing"))); ParamConverters.parseLong(_r) ?: throw ValidationException(listOf(ValidationError(path = "id", message = "must be a valid integer", code = "Type"))) })

                    }
                },
                controllerClass = "controller.admin.token.TokenController",
                methodName = "delete",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:token:delete",
                rateLimit = null,
                freshAuth = false
            )
        )

        // === GroupController 控制器路由 ===

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/group/list",
                method = HttpMethod.GET,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.group.GroupController()
                        
                        val _r = ctrl.list()
                        return JsonContent(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(GatewayGroup.serializer()), _r))

                    }
                },
                controllerClass = "controller.admin.group.GroupController",
                methodName = "list",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:group:list",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/group/get/{id}",
                method = HttpMethod.GET,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.group.GroupController()
                        
                        val _r = ctrl.get(run { val _r = args.first("id") as? String; if (_r == null) throw ValidationException(listOf(ValidationError(path = "id", message = "is required", code = "Missing"))); ParamConverters.parseLong(_r) ?: throw ValidationException(listOf(ValidationError(path = "id", message = "must be a valid integer", code = "Type"))) })
                        return JsonContent(Json.encodeToString(GatewayGroup.serializer().nullable, _r))

                    }
                },
                controllerClass = "controller.admin.group.GroupController",
                methodName = "get",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:group:get",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/group/create",
                method = HttpMethod.POST,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.group.GroupController()
                        
                        val _r = ctrl.create(run {
                                val ct = context.request.contentType?.lowercase() ?: ""
                                if (!ct.contains("application/json")) throw UnsupportedMediaTypeException("Body requires application/json")
                                val raw = context.request.text()
                                val body = try {
                                    Json.decodeFromString(SaveGroupRequest.serializer(), raw)
                                } catch (e: Exception) {
                                    throw ValidationException(listOf(ValidationError(path = "\$", message = "Invalid JSON body", code = "InvalidJson")))
                                }
                                val registry = context.getApplicationContext()?.getOrNull(neton.validation.ValidatorRegistry::class)
                                val validator = registry?.get(SaveGroupRequest::class)
                                val errors = validator?.validate(body)
                                if (!errors.isNullOrEmpty()) throw ValidationException(errors)
                                body
                            })
                        return JsonContent(Json.encodeToString(Long.serializer(), _r))

                    }
                },
                controllerClass = "controller.admin.group.GroupController",
                methodName = "create",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:group:create",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/group/update",
                method = HttpMethod.PUT,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.group.GroupController()
                        
                        return ctrl.update(run {
                                val ct = context.request.contentType?.lowercase() ?: ""
                                if (!ct.contains("application/json")) throw UnsupportedMediaTypeException("Body requires application/json")
                                val raw = context.request.text()
                                val body = try {
                                    Json.decodeFromString(SaveGroupRequest.serializer(), raw)
                                } catch (e: Exception) {
                                    throw ValidationException(listOf(ValidationError(path = "\$", message = "Invalid JSON body", code = "InvalidJson")))
                                }
                                val registry = context.getApplicationContext()?.getOrNull(neton.validation.ValidatorRegistry::class)
                                val validator = registry?.get(SaveGroupRequest::class)
                                val errors = validator?.validate(body)
                                if (!errors.isNullOrEmpty()) throw ValidationException(errors)
                                body
                            })

                    }
                },
                controllerClass = "controller.admin.group.GroupController",
                methodName = "update",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:group:update",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/group/delete/{id}",
                method = HttpMethod.DELETE,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.group.GroupController()
                        
                        return ctrl.delete(run { val _r = args.first("id") as? String; if (_r == null) throw ValidationException(listOf(ValidationError(path = "id", message = "is required", code = "Missing"))); ParamConverters.parseLong(_r) ?: throw ValidationException(listOf(ValidationError(path = "id", message = "must be a valid integer", code = "Type"))) })

                    }
                },
                controllerClass = "controller.admin.group.GroupController",
                methodName = "delete",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:group:delete",
                rateLimit = null,
                freshAuth = false
            )
        )

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

        // === PriceController 控制器路由 ===

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/price/list",
                method = HttpMethod.GET,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.price.PriceController()
                        
                        val _r = ctrl.list()
                        return JsonContent(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ModelPrice.serializer()), _r))

                    }
                },
                controllerClass = "controller.admin.price.PriceController",
                methodName = "list",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:price:list",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/price/get/{id}",
                method = HttpMethod.GET,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.price.PriceController()
                        
                        val _r = ctrl.get(run { val _r = args.first("id") as? String; if (_r == null) throw ValidationException(listOf(ValidationError(path = "id", message = "is required", code = "Missing"))); ParamConverters.parseLong(_r) ?: throw ValidationException(listOf(ValidationError(path = "id", message = "must be a valid integer", code = "Type"))) })
                        return JsonContent(Json.encodeToString(ModelPrice.serializer().nullable, _r))

                    }
                },
                controllerClass = "controller.admin.price.PriceController",
                methodName = "get",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:price:get",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/price/get-by-model",
                method = HttpMethod.GET,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.price.PriceController()
                        
                        val _r = ctrl.getByModel(run { val _r = args.first("model") as? String; if (_r == null) throw ValidationException(listOf(ValidationError(path = "model", message = "is required", code = "Missing"))); _r })
                        return JsonContent(Json.encodeToString(ModelPrice.serializer().nullable, _r))

                    }
                },
                controllerClass = "controller.admin.price.PriceController",
                methodName = "getByModel",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:price:get",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/price/create",
                method = HttpMethod.POST,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.price.PriceController()
                        
                        val _r = ctrl.create(run {
                                val ct = context.request.contentType?.lowercase() ?: ""
                                if (!ct.contains("application/json")) throw UnsupportedMediaTypeException("Body requires application/json")
                                val raw = context.request.text()
                                val body = try {
                                    Json.decodeFromString(SavePriceRequest.serializer(), raw)
                                } catch (e: Exception) {
                                    throw ValidationException(listOf(ValidationError(path = "\$", message = "Invalid JSON body", code = "InvalidJson")))
                                }
                                val registry = context.getApplicationContext()?.getOrNull(neton.validation.ValidatorRegistry::class)
                                val validator = registry?.get(SavePriceRequest::class)
                                val errors = validator?.validate(body)
                                if (!errors.isNullOrEmpty()) throw ValidationException(errors)
                                body
                            })
                        return JsonContent(Json.encodeToString(Long.serializer(), _r))

                    }
                },
                controllerClass = "controller.admin.price.PriceController",
                methodName = "create",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:price:create",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/price/update",
                method = HttpMethod.PUT,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.price.PriceController()
                        
                        return ctrl.update(run {
                                val ct = context.request.contentType?.lowercase() ?: ""
                                if (!ct.contains("application/json")) throw UnsupportedMediaTypeException("Body requires application/json")
                                val raw = context.request.text()
                                val body = try {
                                    Json.decodeFromString(SavePriceRequest.serializer(), raw)
                                } catch (e: Exception) {
                                    throw ValidationException(listOf(ValidationError(path = "\$", message = "Invalid JSON body", code = "InvalidJson")))
                                }
                                val registry = context.getApplicationContext()?.getOrNull(neton.validation.ValidatorRegistry::class)
                                val validator = registry?.get(SavePriceRequest::class)
                                val errors = validator?.validate(body)
                                if (!errors.isNullOrEmpty()) throw ValidationException(errors)
                                body
                            })

                    }
                },
                controllerClass = "controller.admin.price.PriceController",
                methodName = "update",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:price:update",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/price/delete/{id}",
                method = HttpMethod.DELETE,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.price.PriceController()
                        
                        return ctrl.delete(run { val _r = args.first("id") as? String; if (_r == null) throw ValidationException(listOf(ValidationError(path = "id", message = "is required", code = "Missing"))); ParamConverters.parseLong(_r) ?: throw ValidationException(listOf(ValidationError(path = "id", message = "must be a valid integer", code = "Type"))) })

                    }
                },
                controllerClass = "controller.admin.price.PriceController",
                methodName = "delete",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:price:delete",
                rateLimit = null,
                freshAuth = false
            )
        )

        // === UsageLogController 控制器路由 ===

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/log/list-by-user/{userId}",
                method = HttpMethod.GET,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.log.UsageLogController()
                        
                        val _r = ctrl.listByUser(run { val _r = args.first("userId") as? String; if (_r == null) throw ValidationException(listOf(ValidationError(path = "userId", message = "is required", code = "Missing"))); ParamConverters.parseLong(_r) ?: throw ValidationException(listOf(ValidationError(path = "userId", message = "must be a valid integer", code = "Type"))) })
                        return JsonContent(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(UsageLog.serializer()), _r))

                    }
                },
                controllerClass = "controller.admin.log.UsageLogController",
                methodName = "listByUser",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:log:list",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/log/list-by-channel/{channelId}",
                method = HttpMethod.GET,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.log.UsageLogController()
                        
                        val _r = ctrl.listByChannel(run { val _r = args.first("channelId") as? String; if (_r == null) throw ValidationException(listOf(ValidationError(path = "channelId", message = "is required", code = "Missing"))); ParamConverters.parseLong(_r) ?: throw ValidationException(listOf(ValidationError(path = "channelId", message = "must be a valid integer", code = "Type"))) })
                        return JsonContent(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(UsageLog.serializer()), _r))

                    }
                },
                controllerClass = "controller.admin.log.UsageLogController",
                methodName = "listByChannel",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:log:list",
                rateLimit = null,
                freshAuth = false
            )
        )

        // === QuotaController 控制器路由 ===

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/quota/get-by-user/{userId}",
                method = HttpMethod.GET,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.quota.QuotaController(ctx.get(logic.QuotaLogic::class))
                        
                        val _r = ctrl.getByUser(run { val _r = args.first("userId") as? String; if (_r == null) throw ValidationException(listOf(ValidationError(path = "userId", message = "is required", code = "Missing"))); ParamConverters.parseLong(_r) ?: throw ValidationException(listOf(ValidationError(path = "userId", message = "must be a valid integer", code = "Type"))) })
                        return JsonContent(Json.encodeToString(QuotaAccount.serializer(), _r))

                    }
                },
                controllerClass = "controller.admin.quota.QuotaController",
                methodName = "getByUser",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:quota:get",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/quota/grant",
                method = HttpMethod.POST,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.quota.QuotaController(ctx.get(logic.QuotaLogic::class))
                        
                        val _r = ctrl.grant(run {
                                val ct = context.request.contentType?.lowercase() ?: ""
                                if (!ct.contains("application/json")) throw UnsupportedMediaTypeException("Body requires application/json")
                                val raw = context.request.text()
                                val body = try {
                                    Json.decodeFromString(GrantQuotaRequest.serializer(), raw)
                                } catch (e: Exception) {
                                    throw ValidationException(listOf(ValidationError(path = "\$", message = "Invalid JSON body", code = "InvalidJson")))
                                }
                                val registry = context.getApplicationContext()?.getOrNull(neton.validation.ValidatorRegistry::class)
                                val validator = registry?.get(GrantQuotaRequest::class)
                                val errors = validator?.validate(body)
                                if (!errors.isNullOrEmpty()) throw ValidationException(errors)
                                body
                            })
                        return JsonContent(Json.encodeToString(Long.serializer(), _r))

                    }
                },
                controllerClass = "controller.admin.quota.QuotaController",
                methodName = "grant",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:quota:grant",
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/gateway/quota/transactions/{userId}",
                method = HttpMethod.GET,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.admin.quota.QuotaController(ctx.get(logic.QuotaLogic::class))
                        
                        val _r = ctrl.transactions(run { val _r = args.first("userId") as? String; if (_r == null) throw ValidationException(listOf(ValidationError(path = "userId", message = "is required", code = "Missing"))); ParamConverters.parseLong(_r) ?: throw ValidationException(listOf(ValidationError(path = "userId", message = "must be a valid integer", code = "Type"))) })
                        return JsonContent(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(QuotaTransaction.serializer()), _r))

                    }
                },
                controllerClass = "controller.admin.quota.QuotaController",
                methodName = "transactions",
                routeGroup = "admin",
                allowAnonymous = false,
                requireAuth = false,
                permission = "gateway:quota:get",
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

        // === ModelsController 控制器路由 ===

        engine.registerRoute(
            RouteDefinition(
                pattern = "/v1/models",
                method = HttpMethod.GET,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.gateway.ModelsController()
                        
                        return ctrl.list(context.response)

                    }
                },
                controllerClass = "controller.gateway.ModelsController",
                methodName = "list",
                routeGroup = "gateway",
                allowAnonymous = false,
                requireAuth = false,
                permission = null,
                rateLimit = null,
                freshAuth = false
            )
        )

        // === GeminiRelayController 控制器路由 ===

        engine.registerRoute(
            RouteDefinition(
                pattern = "/v1beta/models/{modelAction}",
                method = HttpMethod.POST,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.gateway.GeminiRelayController(ctx.get(neton.logging.LoggerFactory::class).get("controller.gateway.GeminiRelayController"), ctx.get(logic.ChannelLogic::class), ctx.get(logic.TokenLogic::class), ctx.get(logic.QuotaLogic::class))
                        
                        return ctrl.generate(run { val _r = args.first("modelAction") as? String; if (_r == null) throw ValidationException(listOf(ValidationError(path = "modelAction", message = "is required", code = "Missing"))); _r }, context, (context.getAttribute(SecurityAttributes.IDENTITY) as? Identity ?: throw neton.core.http.UnauthorizedException()))

                    }
                },
                controllerClass = "controller.gateway.GeminiRelayController",
                methodName = "generate",
                routeGroup = "gateway",
                allowAnonymous = false,
                requireAuth = false,
                permission = null,
                rateLimit = null,
                freshAuth = false
            )
        )

        // === RelayController 控制器路由 ===

        engine.registerRoute(
            RouteDefinition(
                pattern = "/v1/chat/completions",
                method = HttpMethod.POST,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.gateway.RelayController(ctx.get(neton.logging.LoggerFactory::class).get("controller.gateway.RelayController"), ctx.get(logic.ChannelLogic::class), ctx.get(logic.TokenLogic::class), ctx.get(logic.QuotaLogic::class))
                        
                        return ctrl.chatCompletions(context, (context.getAttribute(SecurityAttributes.IDENTITY) as? Identity ?: throw neton.core.http.UnauthorizedException()))

                    }
                },
                controllerClass = "controller.gateway.RelayController",
                methodName = "chatCompletions",
                routeGroup = "gateway",
                allowAnonymous = false,
                requireAuth = false,
                permission = null,
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/v1/embeddings",
                method = HttpMethod.POST,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.gateway.RelayController(ctx.get(neton.logging.LoggerFactory::class).get("controller.gateway.RelayController"), ctx.get(logic.ChannelLogic::class), ctx.get(logic.TokenLogic::class), ctx.get(logic.QuotaLogic::class))
                        
                        return ctrl.embeddings(context, (context.getAttribute(SecurityAttributes.IDENTITY) as? Identity ?: throw neton.core.http.UnauthorizedException()))

                    }
                },
                controllerClass = "controller.gateway.RelayController",
                methodName = "embeddings",
                routeGroup = "gateway",
                allowAnonymous = false,
                requireAuth = false,
                permission = null,
                rateLimit = null,
                freshAuth = false
            )
        )

        engine.registerRoute(
            RouteDefinition(
                pattern = "/v1/messages",
                method = HttpMethod.POST,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = controller.gateway.RelayController(ctx.get(neton.logging.LoggerFactory::class).get("controller.gateway.RelayController"), ctx.get(logic.ChannelLogic::class), ctx.get(logic.TokenLogic::class), ctx.get(logic.QuotaLogic::class))
                        
                        return ctrl.messages(context, (context.getAttribute(SecurityAttributes.IDENTITY) as? Identity ?: throw neton.core.http.UnauthorizedException()))

                    }
                },
                controllerClass = "controller.gateway.RelayController",
                methodName = "messages",
                routeGroup = "gateway",
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
        return 35
    }

    /**
     * 获取控制器统计信息
     */
    fun getControllerStats(): String {
        return "Controllers: 10, Routes: ${getTotalRoutes()}"
    }
}
