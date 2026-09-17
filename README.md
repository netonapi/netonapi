# neton-application-module-gateway

NanoGate 的网关核心模块：LLM API 中转、协议转换、路由与容错、账务与结算、定价、用量、运营工具。
moduleId `gateway`；路由组 `gateway`（`/v1`、`/v1beta`、`/health`、`/metrics`）、`app`（用户自助）、`admin`（管理台）。

## 包结构

| 包 | 内容 |
|---|---|
| `controller/gateway` | 中转入口：OpenAI（chat / responses / embeddings / images / rerank / audio）、Anthropic `/v1/messages`、Gemini `/v1beta`、`/v1/models`、`/health`、`/metrics` |
| `controller/app` | 用户自助：Key、用量与汇总、充值、兑换码、模型广场、公开接入信息 |
| `controller/admin` | 管理台：渠道（含探测、拉模型）、定价（含导入 / 价源同步）、令牌、日志、结算复核、计价组、兑换码、概览 |
| `logic/RelayEngine` | 中转引擎：限流 → 计价组 → 路由候选 → 预留 → 上游执行（重试、流式预检）→ 结算 |
| `logic/protocol` | 协议适配器（OpenAI / Azure / Anthropic / Gemini / Bedrock / Vertex）、编解码器、SSE 分帧、模型名反向改写、multipart |
| `logic/SettlementLogic` `SettlementWorker` | 两阶段结算与崩溃恢复（见 docs/V004） |
| `logic/PricingCalc` `SalePricing` `ReserveCalc` | 计价、售价轨、预留额估算 |
| `logic/OverviewLogic` `UsageStatsLogic` | 管理台概览与用户用量汇总（都从日志表现算） |
| `job/` | 定时任务：渠道测速、价源同步 |
| `sql/postgresql` | 迁移 V001–V012，编译期嵌入 |

## 管理台信息架构（V012）

NanoGate 是唯一装配 gateway 的发行版（canonical 脚手架装的是 `cs`），所以「本产品的管理台长什么样」
也是本模块的职责：V012 停用与中转站无关的继承页面、把 AI 网关排到第一组、补回没有入口的支付回调页、
预置网关运营 / 财务 / 客服 / 只读四个角色。这些**不能**写进 module-system / infra / member ——
那些是 canonical 模块，别的产品要用签到、提现、开放平台。

因此本模块的 `@Module(dependsOn = ...)` 列了五个模块：不是运行时要用它们的 Logic（只用 system），
而是迁移按 dependsOn 拓扑排序，V012 必须在它要调整的菜单行都插入之后才跑。漏声明时那批 UPDATE
会匹配 0 行且不报错。

## 设计文档

- `docs/V004-durable-settlement-design.md`：结算状态机与恢复
- `docs/upstream-adapters-and-codecs.md`：上游适配器钩子、托管上游（Azure / Bedrock / Vertex）、跨协议保真矩阵、端点与计费规则、运维任务

## 测试

```bash
./gradlew :module-gateway:macosArm64Test -Pneton.database.driver=postgres   # 从 NewGate/newgate 目录
```

单测覆盖编解码器（Anthropic / OpenAI / Gemini 三向）、SigV4、AWS event-stream 解帧、multipart、计价与预留、路由、限流。
端到端行为由 `NewGate/harness/run.sh` 覆盖。

## 约定

- 本模块不带 HTTP 引擎：客户端来自应用绑定的 `HttpClientProvider`，SMTP 之类同理。
- 账务只走 `SettlementLogic` / `QuotaLogic`，不直接改余额列。
- 上游 usage 是计费真源；跨协议转换只改客户端看到的形状，不改账。
