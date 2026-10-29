# NetonAPI 后端总规范

> 本文档描述 NetonAPI 后端的定位、仓库结构、架构分层、配置与 API 通用约定，供开发与对接使用。
> 最后更新：2026-10-29
>
> 开发硬规范见 [ENGINEERING_RULES.md](./ENGINEERING_RULES.md)；模块模板见 [module-template-spec.md](./module-template-spec.md)；
> 权限体系（User/Role/Menu、权限串、通配、super_admin）见 [rbac-spec.md](./rbac-spec.md)。
> 各业务领域的规范（计价、模型目录、API Key、充值、转发错误等）见 README 的文档列表。

---

## 一、定位

NetonAPI 是自托管的 LLM API 中转站，**对标 [new-api](https://github.com/QuantumNous/new-api)**：运营习惯与功能面向 new-api 用户看齐，
代码基于 [Neton](https://github.com/netonframework/neton) 框架独立实现，不是 new-api 的移植或改写。

| 维度 | 说明 |
|------|------|
| 运行形态 | 单进程、单个 Kotlin/Native 静态可执行文件，无 JVM |
| 上游协议 | OpenAI、Anthropic、Gemini，任意方向互转 |
| 前端 | 管理台 [netonapi-frontend](https://github.com/netonapi/netonapi-frontend)、用户控制台 [netonapi-client](https://github.com/netonapi/netonapi-client)（均为 React + Next.js） |

### 技术栈

| 项目 | 选型 |
|------|------|
| 语言 | Kotlin 2.4.20（Multiplatform） |
| 框架 | Neton |
| 编译 | Kotlin/Native → 原生二进制（`application.kexe`） |
| 数据库 | 仅 PostgreSQL；Redis 可选（跨实例共享限流） |
| 认证 | 管理台 / 用户控制台：JWT；LLM 转发：`sk-` API Key |
| 构建 | Gradle 8.14.4 + KSP 2.3.12 |
| 日志 | 结构化日志，按天滚动（`logs/all.log`、`access.log`、`error.log`） |

---

## 二、仓库与模块结构

### 2.1 仓库

本仓（`netonapi-backend`）是**后端发行版**：只做装配与产品配置，业务逻辑都在 canonical 模块仓里。

| 仓库 | 内容 | 引入方式 |
|------|------|---------|
| `neton` | 框架 | `includeBuild` |
| `neton-ai` | LLM 协议编解码 | `includeBuild` |
| `geolite4k` | GeoIP | `includeBuild` |
| `neton-application-contract` | 模块间契约 | `includeBuild` |
| `neton-application-module-system` | 用户 / 角色 / 菜单 / 权限 / 设置 / 审计 | 源码子项目 |
| `neton-application-module-infra` | 文件、定时任务、领域事件等基础设施 | 源码子项目 |
| `neton-application-module-account` | 终端用户账号：注册、登录、资料、找回密码 | 源码子项目 |
| `neton-application-module-member` | 会员：分组、等级等 | 源码子项目 |
| `neton-application-module-payment` | 支付：通道、订单、钱包、充值 | 源码子项目 |
| `neton-application-module-platform` | 开放平台 | 源码子项目 |
| `neton-application-module-gateway` | **NetonAPI 核心**：渠道、模型目录、计价、转发、结算 | 源码子项目 |

源码子项目以 `include(...)` + `projectDir` 引入，各模块仓保留自己的 `settings.gradle.kts`，可独立构建与测试。
`settings.gradle.kts` 写的是 `../../Neton/<repo>`，所以本仓必须落在持有 `Neton/` 的目录的两层深处（见 README）。

### 2.2 装配

模块列表唯一来源：`application/build.gradle.kts` 里的 `ksp { arg("neton.modules", ...) }`，当前为
`system,infra,account,member,payment,platform,gateway`。KSP 据此生成 `GeneratedApplicationModules`，
`migrate` 子命令、启动 precheck 与服务启动共用这一份列表。

---

## 三、架构

### 3.1 分层

```
Controller  ──  HTTP 路由、参数绑定、@Permission、统一响应包装
     │
   Logic    ──  业务逻辑、事务编排
     │
   Table    ──  数据访问（KSP 生成 *TableImpl：CRUD + query / page）
     │
   PostgreSQL
```

- Controller 只做参数解析与调用 Logic，不直接操作 Table。
- 分页接口在 Logic 层返回 `dto.PageResponse<T>`，不直接返回框架内部的分页类型。

### 3.2 路由组

由 `application/config/routing.conf` 定义；控制器按包名归组（`controller.admin.*` → admin，以此类推）。

| 路由组 | 挂载 | 认证 | 用途 |
|--------|------|------|------|
| `gateway` | `/` | `sk-` API Key | LLM 转发：`/v1/chat/completions`、`/v1/messages`、`/v1/embeddings`、Gemini `:generateContent` 等；计费分组由 Key 决定 |
| `admin` | `/admin` | 运营 JWT | 管理台 API |
| `app` | `/app` | 会员 JWT（控制台经 HttpOnly Cookie 代理） | 用户控制台 API |
| `open` | `/platform` | 按接口 | 开放平台 |
| `manage` | `/manage` | 会员 JWT | 账号模块的通用经营者入口，NetonAPI 当前没有挂任何接口 |

各路由组的认证彼此独立：管理员令牌进不了 `/app`，会员令牌进不了 `/admin`，API Key 只能调 `gateway` 组。

### 3.3 健康检查

| 路径 | 说明 |
|------|------|
| `GET /health` | 存活 + 数据库连通（容器 `HEALTHCHECK` 用） |
| `GET /admin/system/health/live` | 存活 |
| `GET /admin/system/health/ready` | 就绪：数据库与 Redis 检查 |

---

## 四、配置

- 配置文件在 `application/config/`：`application.conf`、`database.conf`、`routing.conf`、`storage.conf`、`geolite.conf`。
- 每一项都能用环境变量覆盖：`NETON_` 前缀、`__` 表示点、路径小写（`NETON_SECURITY__JWT__SECRETKEY` → `security.jwt.secretKey`）。
- 产品自身的开关用 `NETONAPI_*`（如 `NETONAPI_CHANNEL_KEY_ENC_KEY`、`NETONAPI_SALE_MARKUP`、`NETONAPI_MIN_MARGIN`、`NETONAPI_PRICEBOOK_TTL_MILLIS`）。
- 运营可调的业务设置存在数据库 `system_settings`，后台「系统设置」修改即时生效（如 `payment.mock.enabled`）。
- 默认端口 `8800`。部署、密钥与反向代理见 `nanogate` 仓的 `DEPLOY.md`。

---

## 五、API 约定

### 5.1 统一响应体（`/admin`、`/app`）

```json
{ "code": 0, "message": "OK", "data": <业务数据> }
```

- `code = 0` 为成功；非 0 为错误，段位由框架 `NetonErrorCode` 定义（`10000-10099` 认证、`10100-10199` 参数、`10200-10299` 资源、`10300-10399` 限流、`20000+` 业务）。
- 业务错误的 `message` 只放**机器码**（`UPPER_SNAKE_CASE`，如 `CHANNEL_COST_SOURCE_REQUIRED`），字段级细节放在校验错误列表里；
  文案由前端语言包按机器码翻译。模块单测 `ErrorMessageGuardTest` 强制这条规则。
- 内部细节（上游返回、拦截原因等）只进日志，不进响应。

LLM 转发接口（`gateway` 组）不用这个信封，按各自协议（OpenAI / Anthropic / Gemini）的原生错误格式返回，见 [relay-errors-spec.md](./relay-errors-spec.md)。

### 5.2 分页

- 请求：`pageNo`（从 1 开始）、`pageSize`。
- 响应 `data`：

```json
{ "list": [ ... ], "total": 100, "page": 1, "size": 20, "totalPages": 5 }
```

### 5.3 认证

| 说明 | 方法 | 路径 |
|------|------|------|
| 管理员登录 | POST | `/admin/system/auth/login` |
| 管理员权限信息 | GET | `/admin/system/auth/get-permission-info` |
| 会员登录 | POST | `/app/auth/login-username` |

请求头 `Authorization: Bearer <accessToken>`。未认证 401，无权限 403。

---

## 六、数据与迁移

- 只支持 PostgreSQL；每个模块的迁移在自己仓的 `sql/postgresql/`，命名 `V<NNN>__<说明>.sql`，按序号递增，发布后不改旧文件。
- 迁移 SQL 在编译期嵌入二进制：`./application.kexe migrate up`（另有 `status` / `verify`）。
  服务启动**不自动迁移**：precheck 发现迁移待执行、失败或不匹配就退出（exit 1）。
- 表名以模块为前缀：`system_*`、`infra_*`、`account_*`、`member_*`、`pay_*`、`gateway_*` 等。

---

## 七、构建、运行与测试

```bash
./gradlew :application:linkDebugExecutableMacosArm64      # Apple silicon 开发构建
./gradlew :application:linkReleaseExecutableLinuxX64      # 发布产物
./application.kexe migrate up && ./application.kexe       # 迁移后启动，监听 :8800
```

- 模块单测：在各模块仓执行 `./gradlew macosArm64Test`。
- 行为测试：`nanogate` 仓的 `harness/run.sh`，在用完即弃的 PostgreSQL 库与隔离的 `redis-server` 上启动真实网关，用 Python 假上游逐场景断言。
- 线上演练：`harness/live-e2e.py`，见 [live-drill-spec.md](./live-drill-spec.md)。

---

## 八、开发约定

- **Controller**：`@Controller("/xxx")` + `@Get` / `@Post` 等注解，KSP 生成路由注册；放在对应路由组的包下（如 `controller.admin.*`）。
- **Model / Table**：`@Table("表名")`、`@Id` 等注解，KSP 生成 `*TableImpl`；Facade `object XxxTable : Table<Xxx, Long> by XxxTableImpl`。
- **整行更新**：`Table.update(...)` 写回整行；更新时在库里的原记录上 `copy(...)`，不要新建对象（否则 `created_at` 等列会被写空）。
- **KSP 缓存**：改了 Model / Table 后若出现 “Unresolved reference *TableImpl”，删除该模块的 `build/generated/ksp` 与 `build/kspCaches` 后全量编译。

若本文与代码不一致，以代码与当前配置为准，并同步修正本文。
