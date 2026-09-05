import kotlin.system.exitProcess
import migration.MigrationCommandRunner
import migration.MigrationStartupPrecheck
import neton.core.Neton
import neton.core.component.NetonContext
import neton.core.component.NetonLifecycle
import neton.core.component.cors
import neton.core.generated.GeneratedNetonConfigRegistry
import neton.database.database
import neton.jobs.jobs
import neton.http.http
import neton.http.client.HttpClient
import neton.http.client.HttpClientProvider
import neton.http.client.create
import neton.http.ktor.KtorHttpAdapter
import neton.redis.redis
import neton.routing.routing
import neton.security.security
import neton.storage.storage
import bridge.GatewayQuotaRechargePaidListener
import bridge.PayRechargeOrderBridge
import logic.MemberGroupPort
import logic.QuotaRechargeLogic
import logic.RechargeOrderPort
import neton.core.event.DomainEventBus
import security.WildcardPermissionEvaluator
import table.MemberTable

// MANIFEST-P2: 模块列表唯一来源是 application/build.gradle.kts 的
// `ksp { arg("neton.modules", "...") }`。KSP 生成 GeneratedApplicationModules,
// migrate / precheck / serve 三处共用 —— 不再手写 import + 三份列表。
import neton.application.generated.GeneratedApplicationModules

fun main(args: Array<String>) {
    // ====== Migrate 子命令路径 ======
    // `./application.kexe migrate <status|up|verify>` — 只跑 migration engine,
    // 不启动 HTTP / scheduler / WebSocket / controllers / seed。SPEC §五。
    if (args.firstOrNull() == "migrate") {
        val code = MigrationCommandRunner.run(args, GeneratedApplicationModules.modules)
        exitProcess(code)
    }

    // ====== Startup migration precheck (SPEC §0.6 红线) ======
    // 正常启动绝不自动 migrate。检查 pending/failed/mismatch,有就 fail-fast。
    // 与上方 migrate 子命令共用同一份模块列表 + ApplicationMigrationSources.collect。
    MigrationStartupPrecheck.check(args, GeneratedApplicationModules.modules)?.let { reason ->
        println("================== STARTUP ABORTED ==================")
        println(reason.trimEnd())
        println("=====================================================")
        exitProcess(1)
    }

    Neton.run(args) {
        configRegistry(GeneratedNetonConfigRegistry)

        // ====== 出站 HTTP 客户端：应用创建、应用绑定、应用关闭 ======
        // 借用方：module-system（短信）、module-payment（支付平台回调）、
        // module-gateway（渠道客户端 —— 按各渠道自己的代理与超时，走下面的 provider 建）。
        //
        // 为什么由应用建而不是各模块自建：模块自建就得自带引擎，带引擎的模块一多，
        // 类路径上会出现多个 `create`。所以引擎只在本工程的 build 文件里出现一次
        // （neton-http-ktor），源码里不出现引擎名 —— 换引擎不需要改这里。
        //
        // 两个模块把这个绑定当**硬要求**：module-system 的 SystemRuntimeBootstrap 与
        // module-gateway 的 GatewayRuntimeBootstrap 都取不到就 error() 启动失败。
        // 宁可启动即炸，也不要运行到发短信/调上游那一刻才发现没有客户端。
        val outboundHttp = HttpClient.create {
            connectMillis = 3_000
            requestMillis = 15_000
            socketMillis = 15_000
        }
        bind(HttpClient::class, outboundHttp)
        // provider 建出来的客户端由**调用方模块自己关闭**（见 HttpClientProvider 的约定）；
        // 这里只负责关掉应用自己持有的那一个。
        bind(HttpClientProvider::class, HttpClientProvider { HttpClient.create(it) })

        // ====== 网关额度充值下单（module-gateway 的 SPI 由装配层实现）======
        // 与下面的 MemberGroupPort 同理：gateway 编译期对 payment 零依赖，payment 也不认识
        // 网关额度语义（它自己的 WalletRechargePaidListener 只管 `wallet:recharge:`）。
        //
        // 桥在 bind 时**拿不到** ctx：bind 收集到 deferredBindings，执行于 Neton.kt:298，
        // 而模块初始化在 :320 —— 那时 PayOrderLogic 还不存在。所以这里只建实例，
        // ctx 在下面 onStart（:333）里 attach；createOrder 只在 HTTP 请求中被调用，远晚于此。
        val rechargeOrders = PayRechargeOrderBridge()
        bind(RechargeOrderPort::class, rechargeOrders)

        // ⚠️ 全应用只能有**一个** onStart：LaunchBuilder.onStart 是赋值而非追加（Neton.kt:256），
        // 再写一个会把这里注册的生命周期钩子整个覆盖掉。需要新的启动期动作就往这个块里加。
        onStart {
            val ctx = get(NetonContext::class)
            ctx.lifecycle.register("outbound-http-client", object : NetonLifecycle {
                override suspend fun start() = Unit
                override suspend fun stop() = outboundHttp.close()
            })
            rechargeOrders.attach(ctx)

            // 网关额度充值到账的监听者。必须在这里注册而不是 gateway 自己的 bootstrap 里：
            // 它要 import payment 的 PayOrderPaidEvent，那条依赖正是上面 RechargeOrderPort
            // 要避免的。时序上安全 —— 模块初始化（:320）已完成，QuotaRechargeLogic 取得到；
            // 而事件总线在 :338 才 seal()，此刻登记监听者还来得及。
            ctx.get(DomainEventBus::class).register(
                GatewayQuotaRechargePaidListener(ctx.get(QuotaRechargeLogic::class))
            )
        }

        // ====== 会员分组 → 计费组（module-gateway 的 SPI 由装配层实现）======
        // gateway 与 member 之间没有编译依赖：gateway 只问「这个用户属于哪个会员组」，
        // 而「由谁回答、按什么规则回答」是**产品策略**，不是通用模块的职责，所以放在这里。
        //
        // 默认**不启用**：设 NEWGATE_MEMBER_GROUP_BILLING=true 才生效（开关在 gateway 的
        // GatewayRuntimeBootstrap 里门控；开着却没绑定下面这个 port 会直接启动失败）。
        //
        // 启用前必须先把会员组映射到 gateway_groups.member_group_id：映射缺失时，有分组的
        // 会员会一律 403 billing_group_not_allowed。那是有意的 —— 宁可拒绝，也不要悄悄按
        // default 组计价（少收的差额不会在任何日志里留痕迹）。
        bind(MemberGroupPort::class, object : MemberGroupPort {
            override suspend fun memberGroupId(userId: Long): Long? = MemberTable.get(userId)?.groupId
        })

        // CORS via DSL —— neton 的 TomlParser 不支持 array 语法，application.conf
        // 里写 [cors] allowedOrigins = [...] 会被吞掉（详见 HttpComponent.kt:36），
        // 走 DSL 绕开 TOML。
        //
        // **origin 一律放开为 `*`**：NanoGate 的两个前端（管理台、用户控制台）在开发期
        // 端口频繁变动，逐一维护 allowlist 会持续踩 CORS。allowCredentials=false 时 `*` 合法
        // （浏览器不发 cookie），跨站因此读不到需要登录态的响应。
        //
        // ⚠️ 这是**硬编码**、不是配置项：想给生产收紧白名单，只能改这段重新编译，
        // 或让反向代理只放行受信 origin。别指望换一份 conf 能改掉它。
        http(::KtorHttpAdapter) {
            cors {
                allowedOrigins = listOf("*")
                allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                allowedHeaders = listOf("*")
                allowCredentials = false
                maxAgeSeconds = 3600
            }
        }

        // database 启动只做连接初始化与探活。
        // 严禁在此或任何 ModuleInitializer 中调用 ensureTable()/ALTER 等 schema 变更逻辑。
        // schema 演进的唯一权威路径是手动 SQL 脚本（sql/{dialect}/V*.sql）。
        // 详见架构边界：neton-docs/docs/spec/migration.md
        database { }

        security {
            // 注入应用脚手架的通配权限评估器（rbac-spec §4.2）。
            // 框架默认 PermissionEvaluator 是精确匹配，不支持 *:*:* 等通配；
            // 必须由脚手架显式覆盖。
            setPermissionEvaluator(WildcardPermissionEvaluator())
        }

        // Redis 装载（SMS code 暂存、限流、token 黑名单等都需要）。
        // 配置文件 config/redis.conf；缺省 host=127.0.0.1 port=6379。
        redis { }

        // 定时任务调度（RP-7-B1）。@Job 代码定义 handler；module-infra 的 DbBackedJobConfigSource
        // 让 infra_jobs 成为运行期真源（启用/停用），JobLogListener 写 infra_job_logs 执行日志。
        // 需在 redis 之后（SINGLE_NODE 依赖 LockManager）、modules 之前（@Job 片段在模块 init 注册）。
        jobs { }

        // 文件存储：用户态上传统一走 default source（spec
        // MODULE_MEMBER_PROFILE_SPEC §4.2）。配置在 config/storage.conf；
        // 第一版 local，后续切 S3 / OSS 不需要业务代码改动。
        storage { }

        // routing { } 已内置限流能力（Redis 优先，无 Redis 降级本地内存）
        // @RateLimit 注解标注的接口将自动生效
        routing { }

        modules(*GeneratedApplicationModules.modules.toTypedArray())
    }
}
