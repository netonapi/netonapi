# Neton Application

基于 [Neton](../neton) 框架构建的企业级后台管理系统，采用 Kotlin/Native 编译为原生二进制，启动快、内存低、单文件部署。

> 开发硬规范：见 [ENGINEERING_RULES.md](./ENGINEERING_RULES.md)
>
> 模块模板规范：见 [docs/module-template-spec.md](./docs/module-template-spec.md)

## 技术栈

| 项目 | 选型 |
|------|------|
| 语言 | Kotlin 2.3 (Multiplatform) |
| 框架 | Neton 1.0.0-beta1 |
| 编译 | Kotlin/Native → 原生二进制 |
| 数据库 | MySQL / PostgreSQL / SQLite |
| 认证 | JWT |
| 构建 | Gradle 8.14 + KSP |
| 日志 | 结构化 JSON，异步写入 |

**运行指标：**

- 冷启动 ~150ms（debug）/ ~3ms（release）
- 内存占用 ~20MB
- 单文件部署，无 JVM 依赖

## 仓库结构

采用**多仓库 + Gradle Composite Build** 架构，主仓库与扩展模块分离：

| 仓库 | 内容 | 引入方式 |
|------|------|---------|
| `neton` | 框架 | `includeBuild("../neton")` |
| `neton-application` | 主应用（module-system + module-infra + application 入口） | 根项目 |
| `neton-application-module-member` | 会员模块 | `includeBuild` |
| `neton-application-module-payment` | 支付模块 | `includeBuild` |
| `neton-application-module-platform` | 开放平台模块 | `includeBuild` |
| `neton-application-admin` | 管理端前端（Vue3 + Ant Design Vue） | 独立仓库 |

目录布局：

```
projects/
├── neton/                              # 框架
├── neton-application/                  # 主仓库（本项目）
│   ├── application/                    # 应用入口
│   │   ├── src/commonMain/kotlin/
│   │   │   ├── Main.kt                # 启动入口
│   │   │   ├── config/                # SecurityConfig 等
│   │   │   └── controller/            # HomeController（健康检查）
│   │   └── config/                    # TOML 配置文件
│   │       ├── application.conf       # 服务器、日志配置
│   │       ├── database.conf          # 数据库连接
│   │       └── routing.conf           # 路由组定义
│   ├── module-system/                  # 系统管理（核心）
│   ├── module-infra/                   # 基础设施
│   ├── sql/                            # 主库迁移脚本
│   │   ├── mysql/
│   │   ├── postgresql/
│   │   └── sqlite/
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── gradle.properties
├── neton-application-module-member/    # 会员模块（独立仓库）
├── neton-application-module-payment/   # 支付模块（独立仓库）
├── neton-application-module-platform/  # 开放平台模块（独立仓库）
└── neton-application-admin/            # 管理端前端
```

## 架构设计

### 分层架构

```
Controller  ──  HTTP 路由处理，参数绑定，权限注解
     │
   Logic    ──  业务逻辑，状态机，事务编排
     │
   Table    ──  数据访问（KSP 自动生成 CRUD）
     │
   DB       ──  MySQL / PostgreSQL / SQLite
```

### 路由组

| 组 | 挂载路径 | 用途 | requireAuth |
|----|---------|------|-------------|
| `admin` | `/admin` | 管理后台 API | true |
| `app` | `/app` | C 端 / 移动端 API | true |
| `open` | `/platform` | 开放平台 API | 按需 |

### 模块依赖关系

```
application
├── module-system        ← 核心，被所有模块依赖
├── module-infra         ← 依赖 system
├── module-member        ← 依赖 system（独立仓库）
├── module-payment       ← 依赖 system（独立仓库）
└── module-platform      ← 依赖 system（独立仓库）
```

## 功能模块

### module-system — 系统管理

RBAC 权限体系、认证、组织架构。

| 功能 | 说明 |
|------|------|
| 认证 | 用户名密码登录、短信登录、社交登录（Google / Telegram）、Token 刷新 |
| 用户管理 | CRUD、启用/禁用、密码重置、个人资料 |
| 角色管理 | CRUD、角色分配、菜单权限分配 |
| 菜单管理 | 树形结构 CRUD |
| 权限管理 | 细粒度权限查询与分配 |
| 部门管理 | 树形组织架构 CRUD |
| 岗位管理 | 岗位 CRUD 与列表 |
| 字典管理 | 字典类型 + 字典数据 CRUD |
| 日志 | 登录日志、操作审计日志 |
| 通知公告 | 系统公告 CRUD |
| 消息系统 | 消息渠道、消息模板、发送记录、短信/邮件/通知模板 |
| 社交用户 | 第三方社交账号绑定管理 |

### module-infra — 基础设施

| 功能 | 说明 |
|------|------|
| 动态配置 | Key-Value 配置管理 |
| 文件管理 | 文件上传、S3 预签名 URL、多存储后端 |
| 存储配置 | 存储后端 CRUD、连通性测试、默认存储切换 |
| 定时任务 | Job CRUD、执行日志、Cron 调度 |
| API 日志 | 访问日志 + 错误日志，请求/响应追踪 |
| Redis 监控 | Redis 健康检查与统计 |

### module-member — 会员体系

| 功能 | 说明 |
|------|------|
| 会员认证 | C 端注册/登录/登出、短信验证、社交注册 |
| 会员管理 | 会员 CRUD、分组、标签 |
| 等级体系 | 等级定义、经验值、自动升级 |
| 积分系统 | 积分发放、积分历史、兑换 |
| 签到系统 | 每日签到奖励、签到记录、连续签到追踪 |
| 收货地址 | 地址 CRUD |

### module-payment — 支付系统

| 功能 | 说明 |
|------|------|
| 支付应用 | 多应用管理、密钥生成 |
| 支付渠道 | 多网关接入、渠道配置 |
| 支付订单 | 下单、状态机流转、订单查询 |
| 退款 | 退款申请与处理 |
| 钱包 | 账户钱包、余额变动 |
| 充值 | 充值套餐、充值记录 |
| 转账 | 账户间转账 |
| 异步通知 | 支付回调查询（Webhook 回调端点待实现） |

### module-platform — 开放平台

| 功能 | 说明 |
|------|------|
| 客户端管理 | 第三方客户端注册、AppID/Secret 生成 |
| API 目录 | API 定义、授权策略、定价配置 |
| 签名验证 | HMAC 请求签名与校验 |
| 计费统计 | API 调用计费、用量统计、账单记录 |

## 快速开始

### 环境要求

- **JDK 17+**（Gradle 构建需要，运行时不需要）
- **Git**
- **MySQL**（或 PostgreSQL / SQLite）

### 1. 克隆项目

```bash
git clone <repository-url>
cd neton-application
```

确保 neton 框架及扩展模块在同级目录：

```
projects/
├── neton/                              # 框架
├── neton-application/                  # 本项目
├── neton-application-module-member/    # 会员模块
├── neton-application-module-payment/   # 支付模块
└── neton-application-module-platform/  # 开放平台模块
```

### 2. 初始化数据库

每个仓库各自包含 `sql/` 目录，需在**同一数据库**上依次执行。

#### MySQL

```bash
# 主库（system + infra）
mysql -u root -p neton-application < sql/mysql/V001__create_tables.sql
mysql -u root -p neton-application < sql/mysql/V002__init_data.sql

# 会员模块
mysql -u root -p neton-application < ../neton-application-module-member/sql/mysql/V001__create_tables.sql
mysql -u root -p neton-application < ../neton-application-module-member/sql/mysql/V002__init_data.sql

# 支付模块
mysql -u root -p neton-application < ../neton-application-module-payment/sql/mysql/V001__create_tables.sql
mysql -u root -p neton-application < ../neton-application-module-payment/sql/mysql/V002__init_data.sql

# 开放平台模块
mysql -u root -p neton-application < ../neton-application-module-platform/sql/mysql/V001__create_tables.sql
mysql -u root -p neton-application < ../neton-application-module-platform/sql/mysql/V002__init_data.sql
```

#### PostgreSQL

```bash
# 主库（system + infra）
psql -U postgres -d neton-application -f sql/postgresql/V001__create_tables.sql
psql -U postgres -d neton-application -f sql/postgresql/V002__init_data.sql

# 会员模块
psql -U postgres -d neton-application -f ../neton-application-module-member/sql/postgresql/V001__create_tables.sql
psql -U postgres -d neton-application -f ../neton-application-module-member/sql/postgresql/V002__init_data.sql

# 支付模块
psql -U postgres -d neton-application -f ../neton-application-module-payment/sql/postgresql/V001__create_tables.sql
psql -U postgres -d neton-application -f ../neton-application-module-payment/sql/postgresql/V002__init_data.sql

# 开放平台模块
psql -U postgres -d neton-application -f ../neton-application-module-platform/sql/postgresql/V001__create_tables.sql
psql -U postgres -d neton-application -f ../neton-application-module-platform/sql/postgresql/V002__init_data.sql
```

#### SQLite

```bash
mkdir -p application/data application/logs

# 主库（system + infra）
sqlite3 application/data/backend.db < sql/sqlite/V001__create_tables.sql
sqlite3 application/data/backend.db < sql/sqlite/V002__init_data.sql

# 会员模块
sqlite3 application/data/backend.db < ../neton-application-module-member/sql/sqlite/V001__create_tables.sql
sqlite3 application/data/backend.db < ../neton-application-module-member/sql/sqlite/V002__init_data.sql

# 支付模块
sqlite3 application/data/backend.db < ../neton-application-module-payment/sql/sqlite/V001__create_tables.sql
sqlite3 application/data/backend.db < ../neton-application-module-payment/sql/sqlite/V002__init_data.sql

# 开放平台模块
sqlite3 application/data/backend.db < ../neton-application-module-platform/sql/sqlite/V001__create_tables.sql
sqlite3 application/data/backend.db < ../neton-application-module-platform/sql/sqlite/V002__init_data.sql
```

### 3. 配置数据库连接

编辑 `application/config/database.conf`，按实际数据库类型配置：

```toml
# MySQL
[default]
driver = "MYSQL"
uri = "mysql://root:123456@localhost:3306/neton-application"

# PostgreSQL
# [default]
# driver = "POSTGRESQL"
# uri = "postgresql://postgres:123456@localhost:5432/neton-application"

# SQLite
# [default]
# driver = "SQLITE"
# uri = "sqlite://data/backend.db"
```

### 4. 编译

```bash
# macOS ARM64 (Apple Silicon)
./gradlew :application:linkDebugExecutableMacosArm64

# macOS x64
./gradlew :application:linkDebugExecutableMacosX64

# Linux x64
./gradlew :application:linkDebugExecutableLinuxX64

# Linux ARM64
./gradlew :application:linkDebugExecutableLinuxArm64
```

> 首次编译需下载 Kotlin/Native 工具链，耗时较长。后续增量编译较快。

### 5. 运行

**必须从 `application` 目录启动**，以正确加载配置：

```bash
cd application
./build/bin/macosArm64/debugExecutable/application.kexe
```

输出：

```
░█▀█░█▀▀░▀█▀░█▀█░█▀█
░█░█░█▀▀░░█░░█░█░█░█
░▀░▀░▀▀▀░░▀░░▀▀▀░▀░▀░

Neton 1.0.0-beta1
Kotlin/Native Runtime

Adapter     : Ktor CIO
Environment : dev
Port        : 7080
Cold Start  : 154 ms

Ready → http://localhost:7080
```

### 6. 验证

```bash
curl http://localhost:7080/
# {"status":"ok","service":"neton-application","version":"1.0.0"}
```

## 配置说明

### application.conf

```toml
[application]
name = "neton-application"
debug = false

[server]
port = 7080
host = "0.0.0.0"

[database]
driver = "MYSQL"
uri = "mysql://root:123456@localhost:3306/neton-application"

[logging]
level = "INFO"

[logging.async]
enabled = true
queueSize = 8192
flushEveryMs = 200
flushBatchSize = 64
shutdownFlushTimeoutMs = 2000

[[logging.sinks]]
name = "access"
file = "logs/access.log"
levels = "INFO"
route = "http.access"

[[logging.sinks]]
name = "error"
file = "logs/error.log"
levels = "ERROR,WARN"

[[logging.sinks]]
name = "all"
file = "logs/all.log"
levels = "ALL"
```

### database.conf

```toml
[default]
driver = "MYSQL"
uri = "mysql://root:123456@localhost:3306/neton-application"
debug = false
```

### routing.conf

```toml
[[groups]]
group = "admin"
mount = "/admin"
requireAuth = true

[[groups]]
group = "app"
mount = "/app"
requireAuth = true

[[groups]]
group = "open"
mount = "/platform"
```

### 环境覆盖

支持按环境加载不同配置：

```bash
# 开发环境（默认）
./application.kexe

# 生产环境 → 加载 application.prod.conf 覆盖
./application.kexe --env=prod
```

配置优先级：CLI 参数 > 环境变量 > 环境配置文件 > 基础配置文件 > 框架默认值

## 编译目标

| 平台 | Target | 产物路径 |
|------|--------|---------|
| macOS ARM64 | `macosArm64` | `application/build/bin/macosArm64/debugExecutable/` |
| macOS x64 | `macosX64` | `application/build/bin/macosX64/debugExecutable/` |
| Linux x64 | `linuxX64` | `application/build/bin/linuxX64/debugExecutable/` |
| Linux ARM64 | `linuxArm64` | `application/build/bin/linuxArm64/debugExecutable/` |
| Windows x64 | `mingwX64` | `application/build/bin/mingwX64/debugExecutable/` |

Release 构建：将 `linkDebugExecutable` 替换为 `linkReleaseExecutable`。

## 容器化部署

```dockerfile
FROM scratch
COPY build/bin/linuxX64/releaseExecutable/application.kexe /app
COPY application/config/ /config/
EXPOSE 7080
ENTRYPOINT ["/app", "--env=prod"]
```

```bash
# 构建镜像（约 10MB）
docker build -t neton-application .
docker run -p 7080:7080 neton-application
```

## 开发指南

### 添加新 Controller

```kotlin
// module-system/src/commonMain/kotlin/controller/admin/example/ExampleController.kt
package controller.admin.example

import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.Post
import neton.core.annotations.Permission

@Controller("/example")
class ExampleController(private val logic: ExampleLogic) {

    @Get("/list")
    @Permission("system:example:list")
    suspend fun list(page: Int = 1, size: Int = 20) = logic.page(page, size)

    @Post("/create")
    @Permission("system:example:create")
    suspend fun create(request: CreateExampleRequest): Long = logic.create(request)
}
```

KSP 自动生成路由注册代码，无需手动配置路由。

### 添加新 Model + Table

```kotlin
// model/Example.kt
@Serializable
@Table("system_examples")
data class Example(
    @Id val id: Long = 0,
    val name: String,
    val status: Int = 0,
    @SoftDelete val deleted: Int = 0,
    @CreatedAt val createdAt: String? = null,
    @UpdatedAt val updatedAt: String? = null
)

// table/ExampleTable.kt（Facade，可添加自定义方法）
object ExampleTable : Table<Example, Long> by ExampleTableImpl
```

KSP 自动生成 `ExampleTableImpl`、`ExampleMeta`、`ExampleRowMapper` 等 6 个文件。

### 项目构建命令

```bash
# 仅编译（不链接）
./gradlew :application:compileKotlinMacosArm64

# 编译 + 链接
./gradlew :application:linkDebugExecutableMacosArm64

# 清理重建
./gradlew clean :application:linkDebugExecutableMacosArm64

# 单独运行 KSP 代码生成
./gradlew :module-system:kspKotlinMacosArm64
```

## 相关文档

| 文档 | 说明 |
|------|------|
| **SPEC.md** | 项目架构、API 规范、配置规范 |

## License

Private - All Rights Reserved
