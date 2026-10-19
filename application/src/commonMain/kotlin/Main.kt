import kotlin.system.exitProcess
import migration.MigrationCommandRunner
import migration.MigrationStartupPrecheck
import neton.core.Neton
import neton.core.component.NetonContext
import neton.core.component.NetonLifecycle
import neton.core.component.cors
import neton.core.generated.GeneratedNetonConfigRegistry
import neton.database.database
import neton.database.dsl.eq
import neton.jobs.JobScheduler
import neton.jobs.jobs
import neton.http.http
import neton.http.client.HttpClient
import neton.http.client.HttpClientProvider
import neton.http.client.create
import neton.redis.redis
import neton.routing.routing
import neton.security.security
import neton.storage.storage
import bridge.GatewayInviteRewardBridge
import bridge.GatewayQuotaRechargePaidListener
import bridge.GatewayQuotaRechargeRefundedListener
import bridge.GatewayQuotaRechargeClosedListener
import bridge.PayRechargeOrderBridge
import channel.PayCallbackUrl
import channel.PayCallbackHooks
import channel.payNotifyUrlOf
import job.PAY_RECONCILE_JOB_ID
import logic.MemberDirectoryPort
import logic.MemberGroupPort
import logic.QuotaRechargeLogic
import logic.RechargeOrderPort
import port.MemberInviteRewardRegistry
import neton.core.event.DomainEventBus
import neton.logging.Logger
import neton.logging.LoggerFactory
import table.AccountTable
import table.MemberProfileTable

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
        // 这里的普通客户端只给**地址写在部署配置里**的出站调用（人机验证、价目表同步）。
        // 地址来自后台运行期数据的（短信 / 邮件渠道、支付平台网关、网关渠道）一律走受控客户端：
        // module-system 按 application.conf [egress] 绑定 GuardedHttpClientProvider，经下面的 provider 建。
        //
        // 为什么由应用建而不是各模块自建：模块自建就得自带引擎，带引擎的模块一多，
        // 类路径上会出现多个 `create`。引擎是 neton-http 内置的（基于 netonstream io），
        // 服务端 `http { }` 与这里的 `HttpClient.create { }` 都是它，源码里不出现引擎名。
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
        // SMTP 发信（module-system 的 EmailProvider 用）：neton-http 内置实现（netonstream io + tls）
        bind(neton.http.mail.SmtpTransport::class, neton.http.mail.defaultSmtpTransport())

        // 账号注册策略：account 的默认策略只开短信注册（PHONE_SMS），而 NetonAPI 的控制台是
        // 用户名 + 密码自助注册（/register），不绑这条就会 REGISTER_MODE_DISABLED。短信保留为可选。
        bind(
            port.AccountAuthPolicy::class,
            port.AccountAuthPolicy(
                registerModes = listOf(port.AccountAuthPolicy.MODE_USERNAME_PASSWORD, port.AccountAuthPolicy.MODE_PHONE_SMS),
                defaultRegisterMode = port.AccountAuthPolicy.MODE_USERNAME_PASSWORD,
            ),
        )

        // ====== 网关额度充值下单（module-gateway 的 SPI 由装配层实现）======
        // 与下面的 MemberGroupPort 同理：gateway 编译期对 payment 零依赖，payment 也不认识
        // 网关额度语义（它自己的 WalletRechargePaidListener 只管 `wallet:recharge:`）。
        //
        // 桥在 bind 时**拿不到** ctx：bind 收集到 deferredBindings，执行于 Neton.kt:298，
        // 而模块初始化在 :320 —— 那时 PayOrderLogic 还不存在。所以这里只建实例，
        // ctx 在下面 onStart（:333）里 attach；createOrder 只在 HTTP 请求中被调用，远晚于此。
        val rechargeOrders = PayRechargeOrderBridge()
        bind(RechargeOrderPort::class, rechargeOrders)

        // ====== 支付回调地址（module-payment 的 hook 由装配层给）======
        // 部署在哪个域名下是**部署事实**，payment 模块无从知道，所以它把 notifyUrlOf / returnUrlOf
        // 留成默认空串，等装配层经 PayCallbackHooks 填。不绑的话 PaymentRuntimeBootstrap 回落到两个 hook 全空：
        // 下单照样成功、支付照样到账（PayOrderReconcileJob 每十分钟查一次单兜底），
        // 只是渠道永不回调，而且用户付完停在渠道自己的页面上回不来。
        //
        // 必须在 bind 阶段就位：PaymentRuntimeBootstrap 在模块初始化（Neton.kt:320）里用 getOrNull
        // 把 hook **读走一次**，晚一步就永远是 null（与下面邀请奖励注册表同一条时序）。
        // 两个值声明在这里而不是更靠后的 bind 区，是因为 onStart 要拿它们记日志 ——
        // lambda 捕获不到在它之后才声明的局部变量。
        //
        // 平台清单不在这里重列，只把两个地址交给 payment（它自己调 PayPlatformRegistry.default）：抄一份清单的话，
        // 日后 payment 多接一家支付公司，这份副本会静默过期 —— 表现是那家的渠道一律
        // 「不可用的支付通道」，而这条错误信息与「渠道被关了」完全一样。
        val notifyBase = PayCallbackUrl.of(neton.core.config.getEnv(ENV_PAY_NOTIFY_BASE))
        val returnUrl = PayCallbackUrl.of(neton.core.config.getEnv(ENV_PAY_RETURN_URL))
        // 只给地址：平台请求用的受控出站客户端由 payment 自己按运维出站策略（application.conf [egress]）建，
        // 渠道里的网关地址是后台数据，不能走这里的普通客户端。
        bind(
            PayCallbackHooks::class,
            PayCallbackHooks(
                notifyUrlOf = payNotifyUrlOf(notifyBase.value),
                // return_url 与渠道无关（付完都回控制台账单页），所以忽略入参。
                // 订单号不用带在地址里：渠道回跳时会附上自己的商户单号（我们的 rechargeId 就在里面）。
                returnUrlOf = { returnUrl.value },
            ),
        )

        // ====== 邀请奖励 → 网关额度（module-member 的扩展点由装配层实现）======
        // member 报「邀请达成」这个事实，不认识额度；gateway 管额度，不认识邀请。注册表只能在这里 bind。
        //
        // 时序比 RechargeOrderPort 那个桥更紧：member 的 MemberRuntimeBootstrap 在模块初始化（:320）里用 getOrNull
        // 把注册表**读走一次**存进 MemberInviteLogic 的构造参数 —— 所以它必须在 bind 阶段（:298）就位，
        // 晚一步就永远是 null（那正是此前的状态：邀请照记，奖励一份也发不出，且无任何日志）。
        // 而桥需要的 QuotaLogic 在 bind 阶段还不存在，所以照旧走 deferred-attach：
        // 注册表按引用持有这个实例，onStart 里挂上 ctx 后，它后续的变化注册表看得见。
        //
        // 额度默认 0 = 不奖励，必须显式设 NEWGATE_INVITE_REWARD_INVITER / _INVITEE 才发。
        val inviteRewards = GatewayInviteRewardBridge()
        bind(MemberInviteRewardRegistry::class, MemberInviteRewardRegistry(listOf(inviteRewards)))

        // ⚠️ 全应用只能有**一个** onStart：LaunchBuilder.onStart 是赋值而非追加（Neton.kt:256），
        // 再写一个会把这里注册的生命周期钩子整个覆盖掉。需要新的启动期动作就往这个块里加。
        onStart {
            val ctx = get(NetonContext::class)
            ctx.lifecycle.register("outbound-http-client", object : NetonLifecycle {
                override suspend fun start() = Unit
                override suspend fun stop() = outboundHttp.close()
            })
            rechargeOrders.attach(ctx)
            // 挂上 QuotaLogic 与 logger，并把生效的邀请奖励额度念一遍 —— 运营确认「奖励到底开没开」
            // 只有这一行日志可看（未装配时整条链路是静默的，契约如此）。
            inviteRewards.attach(ctx)

            // 支付回调地址配没配，运行期只有这里看得出来：没配的话下单照样成功、钱照样到账
            // （对账任务兜底），症状要等到用户问「付完了怎么页面没动」才浮现。所以照邀请奖励
            // 那行的做法，把生效值在启动时念出来。
            val payLog = ctx.get(LoggerFactory::class).get("payment.callback-url")
            // 后果由调用方逐项给：两项退化在不同地方，写成一句共用文案的话，
            // 其中一行会说谎（return_url 没配与「渠道会不会回调」毫无关系），
            // 而运维读到那行会去查一条没坏的路 —— 那比没有这行日志更费时间。
            // 这里写 job id（`pay-order-reconcile`）而不是类名 PayOrderReconcileJob：运维照着这行日志
            // 去后台任务页找，那一列显示的是 @Job.id（DbBackedJobConfigSource 用 `name = def.id` 建行），
            // 写类名的话他搜不到 —— 日志点了个在界面上不存在的名字，等于没点。
            // 「前提是它在后台是启用的」也不能省：infra_jobs 是运行期调度真源，那个任务可以被停用，
            // 而一旦停用，这行 WARN 承诺的兜底就不存在了（钱到账、额度永远不发）。
            reportCallbackUrl(
                payLog, ENV_PAY_NOTIFY_BASE, notifyBase, "notify_url 基址",
                "渠道不会主动回调，到账只能靠定时任务 pay-order-reconcile 查单兜底"
                    + "（十分钟一轮，前提是它在后台是启用的）。",
            )
            reportCallbackUrl(
                payLog, ENV_PAY_RETURN_URL, returnUrl, "return_url",
                "用户付完停在渠道自己的页面上回不来，「跳出去 → 回来看到已到账」的体验不成立。",
            )

            // 充值汇率：不设 = 充值功能整个不可用（每次下单 500），而下单要等用户真来点才会发现。
            // 格式错是明确的配置错误，直接拒绝启动；没设则可能是有意不开放充值（只用兑换码），记 ERROR 不拦。
            val rateRaw = neton.core.config.getEnv(QuotaRechargeLogic.ENV_QUOTA_PER_PRICE_UNIT)?.trim()
            when {
                rateRaw.isNullOrEmpty() -> payLog.error(
                    "${QuotaRechargeLogic.ENV_QUOTA_PER_PRICE_UNIT} 未设置：充值功能不可用，用户每次下单都会失败。" +
                        "要收钱就设成「每 1 单位支付金额（人民币为分）发放多少 μUSD 额度」的正整数。"
                )
                (rateRaw.toLongOrNull() ?: 0L) <= 0L -> error(
                    "${QuotaRechargeLogic.ENV_QUOTA_PER_PRICE_UNIT} 必须是正整数（μUSD / 支付金额最小单位），当前是 '$rateRaw'"
                )
                else -> payLog.info("充值汇率 ${QuotaRechargeLogic.ENV_QUOTA_PER_PRICE_UNIT}=$rateRaw μUSD / 支付金额最小单位")
            }

            // notify_url 没生效时（没配与写错两态 value 都是空串，见 PayCallbackUrl），到账只剩
            // 对账任务这一条路；而那条路在后台可以被停用 —— infra_jobs 是运行期调度真源，
            // 代码 @Job 只定义 handler。两条同时断掉的后果是「钱到账了、额度永远不发」，
            // 而且没有任何一处会说这件事：用户只看到付了钱没额度，运维只看到订单一直待支付。
            // 上一行 WARN 已经承诺了「靠对账任务兜底」，所以那个承诺不成立时必须响。
            //
            // 必须问 JobScheduler.snapshot() 而不是 JobRegistry：JobsComponent.prepare 把 jobs.conf
            // 与 DB override 合并后的结果只交给了 scheduler（Neton.kt:321），registry 里那份仍是
            // 代码默认值 enabled=true —— 读它会得到「一直是启用的」这个假答案。
            // 时序上安全：prepare 在 :321，本块在 :333 的 onStart 里。
            // 局限：只在启动时查一次，运行期在后台停用不会重新检查（那是一次显式操作，
            // 后果写在 DEPLOY.md 里）。id 用常量而不是字面量，理由见 PAY_RECONCILE_JOB_ID 的注释。
            if (notifyBase.value.isEmpty()) {
                val reconcile = ctx.getOrNull(JobScheduler::class)
                    ?.snapshot()?.firstOrNull { it.id == PAY_RECONCILE_JOB_ID }
                if (reconcile != null && !reconcile.enabled) {
                    payLog.error(
                        "两条到账路径同时断掉：$ENV_PAY_NOTIFY_BASE 未生效，而定时任务 " +
                            "$PAY_RECONCILE_JOB_ID 处于停用状态 —— 真实支付的钱会到账，但额度永远不会发放。" +
                            "启用那个任务、或配上回调基址，二者至少留一条。",
                        mapOf("env" to ENV_PAY_NOTIFY_BASE, "job" to PAY_RECONCILE_JOB_ID),
                    )
                }
            }

            // 网关额度充值到账的监听者。必须在这里注册而不是 gateway 自己的 bootstrap 里：
            // 它要 import payment 的 PayOrderPaidEvent，那条依赖正是上面 RechargeOrderPort
            // 要避免的。时序上安全 —— 模块初始化（:320）已完成，QuotaRechargeLogic 取得到；
            // 而事件总线在 :338 才 seal()，此刻登记监听者还来得及。
            ctx.get(DomainEventBus::class).register(
                GatewayQuotaRechargePaidListener(ctx.get(QuotaRechargeLogic::class))
            )
            // 成对：退款时按比例扣回额度，否则「退了钱额度还在」
            ctx.get(DomainEventBus::class).register(
                GatewayQuotaRechargeRefundedListener(ctx.get(QuotaRechargeLogic::class))
            )
            ctx.get(DomainEventBus::class).register(
                GatewayQuotaRechargeClosedListener(ctx.get(QuotaRechargeLogic::class))
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
            // 会员组属于会员档案（member），不在账号表上；没有档案 = 未分组。
            override suspend fun memberGroupId(userId: Long): Long? =
                MemberProfileTable.oneWhere { model.MemberProfile::userId eq userId }?.groupId
        })

        // ====== 会员名录（module-gateway 写用户级计费例外时校验 userId）======
        // 同一个分工：gateway 不认识 member，判据由装配层给。没有它，管理端把 10001 打成
        // 100001 会得到 200，例外静静躺在 gateway_group_overrides 里，客户仍按 default 组计价 ——
        // 谈判价没生效而**双方都不报错**（客户按标准价付了钱，运营以为给了折扣）。
        //
        // 判据是 `status == NORMAL`，不是 `AccountTable.get(id) != null`：account 的自助注销
        // （AccountAuthLogic.deleteOwnAccount）只把 status 置 DELETED、**不动 deleted 列** ——
        // 它的 KDoc 明说「记录保留，后台账号管理仍看得到」，所以「查得到」对注销会员也成立。
        // 写成白名单也是 account 自己要求的：AccountStatus 的 KDoc 用红字标了「判断能不能用这个
        // 账号要写 status == NORMAL，不要写 status != DISABLED」，否定式在新增状态时会默默放行，
        // DELETED 当初就是这么被漏掉的。
        //
        // DISABLED 一并拒绝是有意的：不自创第二套「账号可用性」判据，与 account 的登录/刷新
        // （`status != NORMAL` 即拒）保持一致。代价是「客户被禁用但合同已签」时要等解禁再配，
        // 那不是阻塞；而两套判据各自漂移，是这里最不想要的那种静默不一致。
        bind(MemberDirectoryPort::class, object : MemberDirectoryPort {
            override suspend fun isChargeableMember(userId: Long): Boolean =
                AccountTable.get(userId)?.status == model.AccountStatus.NORMAL
        })

        // CORS via DSL —— neton 的 TomlParser 不支持 array 语法，application.conf
        // 里写 [cors] allowedOrigins = [...] 会被吞掉（详见 HttpComponent.kt:36），
        // 走 DSL 绕开 TOML。
        //
        // **origin 一律放开为 `*`**：NetonAPI 的两个前端（管理台、用户控制台）在开发期
        // 端口频繁变动，逐一维护 allowlist 会持续踩 CORS。allowCredentials=false 时 `*` 合法
        // （浏览器不发 cookie），跨站因此读不到需要登录态的响应。
        //
        // ⚠️ 这是**硬编码**、不是配置项：想给生产收紧白名单，只能改这段重新编译，
        // 或让反向代理只放行受信 origin。别指望换一份 conf 能改掉它。
        http {
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
        // schema 演进的唯一权威路径是 `application.kexe migrate up`（各模块 sql/postgresql/V*.sql 编译进二进制）。
        // 详见架构边界：neton-docs/docs/spec/migration.md
        database { }

        // Authentication and permission evaluation are wired by module-system (config.SystemSecurityConfig).
        security { }

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

        // 业务模块 + 应用自身的 @Controller（如 HomeController）；后者由 KSP 生成的
        // GeneratedInitializer 注册，必须显式传入，框架不再隐式调用它。
        modules(*GeneratedApplicationModules.modules.toTypedArray(), neton.core.generated.GeneratedInitializer)
    }
}

/**
 * 后端公网基址：渠道服务器要能从它访问到 `/app/pay/channel-notify/{channelCode}`
 * （路径那半截的真值在 `channel.PAY_NOTIFY_ROUTE`，改那里必须连这里与 DEPLOY.md 一起改）。
 *
 * 这是**渠道能访问到我们的地址**，不是容器内部地址 —— 填 `http://localhost:7080`
 * 在本地跑得通、上线后一律回调失败，所以部署时要填反向代理对外的 origin。
 */
private const val ENV_PAY_NOTIFY_BASE = "NEWGATE_PAY_NOTIFY_BASE"

/** 用户付完跳回的控制台页面绝对地址（通常是账单页）。 */
private const val ENV_PAY_RETURN_URL = "NEWGATE_PAY_RETURN_URL"

/**
 * 把一个回调地址的配置状态记进启动日志。三态三个级别，判据见 [PayCallbackUrl]：
 *
 * - 写错 → ERROR：那是有人要去改的配置错误，静默接受它会让**每一笔**真实支付都下不了单。
 * - 没配 → WARN：合法但会退化，退化到哪里由 [unsetConsequence] 逐项说清（两项不相同）。
 *   沙箱部署可以忽略这一行。
 * - 能用 → INFO：与邀请奖励那行同理，运营要能一眼确认生效的到底是哪个地址。
 */
private fun reportCallbackUrl(
    log: Logger,
    env: String,
    url: PayCallbackUrl,
    what: String,
    unsetConsequence: String,
) {
    val fields = mapOf("env" to env, "value" to url.value)
    when {
        url.problem != null -> log.error(
            "$what 配置非法，已按未配置处理：${url.problem}",
            fields,
        )
        url.isUnset -> log.warn(
            "$what 未配置（$env 为空）：$unsetConsequence 沙箱部署可忽略本行。",
            fields,
        )
        else -> log.info("$what 已生效：${url.value}", fields)
    }
}
