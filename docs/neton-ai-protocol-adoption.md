# gateway 采用 neton-ai 协议层：业务侧规范

状态：Draft v1（2026-10-14）。框架能力的规格在 neton-docs 的
[`spec/ai.md`](../../neton-docs/docs/zh-hans/spec/ai.md)（以下简称「框架规格」）。本文只写**中转站业务**：
哪些东西下沉到框架、哪些留在 gateway、以及新增协议能力带来的计费与运营规则。

## 1. 边界

**原则：协议是框架能力，钱和渠道是业务。** 这与 new-api 把 RelayKit 拆成独立协议模块的做法一致。RelayKit 是 AGPL-3.0，
只参考其公开文档描述的行为，不复制代码。

| 下沉到 neton-ai 协议层 | 留在 gateway |
|---|---|
| 四种文本协议的报文模型、编解码、跨协议转换（框架规格 §3–§5） | `RelayEngine`：限流 → 计价组 → 路由候选 → 预留 → 上游执行 → 结算 |
| SSE 分帧、AWS event-stream、增量 UTF-8 解码（§6） | 渠道、多 Key、模型映射与别名、优先级、权重、亲和、重试策略 |
| 托管上游的鉴权与路径：Azure、SigV4、Vertex OAuth（§3.2） | 计价（`PricingCalc` / `SalePricing`）、预留（`ReserveCalc`）、两阶段结算、配额、兑换码 |
| 用量事实：统一字段、完整性状态、上游原始 usage（§7） | TPM、并发限流（`RateLimiter`）；按请求计数的部分可改用框架的 `@RateLimit` |
| 上游错误分类与按客户端协议渲染（§8） | 渠道测速、价源同步、`/metrics`、运营后台 |
| 厂商配置文件（§9） | 渠道层面对厂商配置的覆盖（存在渠道的 `provider_config` 里） |
| 媒体解析**钩子**（§5.4） | 媒体解析的**实现**：下载、SSRF 防护（`NetGuard`）、大小上限 |
| 模型名推理尾缀**解析工具**（§5.4） | 是否启用尾缀、按渠道或计价组设定的开关 |
| 损耗策略的机制（§5.3） | 每个渠道采用哪种策略 |
| — | 异步任务（图片、视频等）插件 |

## 2. 现有代码的迁移映射

| gateway 现有 | 去向 |
|---|---|
| `logic/protocol/codec/*`（以 OpenAI Chat 为中枢的三向编解码） | 由框架的 IR 编解码替代；现有单测转成行为等价的回归用例 |
| `SseEventFramer`、`Utf8StreamDecoder`、`aws/EventStreamDecoder` | 框架 `neton.ai.protocol.stream` |
| `aws/SigV4`、`vertex/VertexAuth` | 框架 `neton.ai.protocol.transport` |
| `ProtocolAdapter` 的 `extractUsage`、`streamUsageAccumulator` | 框架 `StreamState` 与用量模型 |
| `ProtocolAdapter.errorBodyFor` | 框架错误渲染 |
| `OpenAiAdapter`、`AnthropicAdapter`、`GeminiAdapter`、`AzureOpenAiAdapter`、`BedrockAdapter`、`VertexAdapters` | 拆开：协议与托管部分进框架；渠道字段到框架参数的装配留在 gateway |
| `ModelRename`（响应与流中的模型名反向改写） | **留在 gateway**。这是中转站的展示策略，通过框架 `StreamState` 的钩子实现 |
| `MultipartForm` | 框架提供 multipart 报文模型；与计费相关的字节统计留在 gateway |

## 3. 端点能力目标

| 端点 | 现状 | 目标 | 计费口径 |
|---|---|---|---|
| `/v1/chat/completions`、`/v1/messages`、`:generateContent` | 三向互转 | 四协议两两互转（经框架 IR） | 上游原生 usage |
| `/v1/responses` | 仅同协议透传 | **跨协议**（上游可以是 Anthropic、Gemini） | 上游原生 usage；流式取终止事件 |
| `/v1/realtime`、`GET /v1/responses`（WebSocket） | 无 | 新增（框架规格 §3.4） | 见第 4.4 节 |
| embeddings、rerank、images（生成、编辑）、audio（三种） | 已有（多为仅 OpenAI 系上游） | 维持；按需补 embeddings 跨协议 | 维持现有规则 |
| batches | 无 | 下一期 | 按批价 |

## 4. 新增的业务规则

### 4.1 用量与计费

- **上游 usage 是唯一的计费真源**。跨协议转换只改变客户端看到的形状，不改账。结算读取框架 `Usage.raw` 的上游原始结构。
- 缓存写入按 **5 分钟与 1 小时**两档分别计价，缓存读取单独计价；推理 token、音频 token、图像 token 能分开时分开计价。定价表需要增加这些计价项，有对应的 SQL 迁移。
- 用量完整性的结算策略：
  - `COMPLETE`：按实际结算；
  - `PARTIAL`（截断、取消）：按已知部分结算，未知部分不按 0 处理，而是按预留额兜底，并标记待复核；
  - `UNKNOWN`：按预留额结算，并进入结算复核队列。
  - 按次计价的模型，非流式 2xx 且没有 usage 时视为完整（沿用现有规则）。

### 4.2 损耗策略

- 默认 `allow`，诊断写入请求日志。
- 涉及工具调用的渠道建议使用 `safe`：工具行为会发生变化时直接拒绝转换，避免"看起来成功、实际调用了错误的工具"。
- 管理台展示每次请求的转换路径、质量等级与诊断，便于客服排查。

### 4.3 推理尾缀

- 是否解析 `-thinking`、`-high` 这类模型名尾缀，按渠道或计价组配置，默认关闭。
- 解析后实际使用的推理预算计入计费依据（推理 token 由上游 usage 给出）。

### 4.4 实时协议

- Realtime 与 Responses WebSocket 按上游事件中给出的 usage 计费；会话时长作为预留与风控依据。
- 并发连接数计入令牌的并发限额。
- 连接管理使用 `neton-websocket`，停机时按其规范关闭会话。

### 4.5 媒体解析

- 只允许 `https`；经 `NetGuard` 校验目标地址；限制大小与超时。
- 下载的字节可以计入请求的计费依据（按渠道配置）。

### 4.6 Gemini thought signature（现存缺陷，优先处理）

- **现状**：gateway 的 `OpenAiToGeminiRequest` 不写任何 thought signature。客户端用 OpenAI 或 Anthropic 格式、上游是 Gemini 3 时，多轮工具调用的第二轮会被上游以 400 拒绝。同协议透传不受影响。
- **处理**：不等框架迁移完成，先在 gateway 现有编解码器里按框架规格 §4.5 的规则修复（对齐 new-api 最新版：每条 assistant 消息的第一个 `functionCall` 写入占位值，没有调用时写到第一个文本部分；`functionResponse` 去掉 `id`）。框架迁移完成后，由框架接管这一逻辑。
- **开关**：渠道级配置，默认开启。
- **验证**：harness 增加"OpenAI 格式客户端 → Gemini 3 上游 → 两轮工具调用"的用例。修复前必须先复现出 400。

## 5. 迁移步骤与回归

1. 框架完成第 1–3 步（框架规格 §12）。
2. gateway 在一个分支上切换到框架协议层，**行为等价**：现有编解码、SigV4、event-stream、multipart、计价与预留、路由、限流的单测全部通过；`NewGate/harness/run.sh` 端到端测试全部通过。
3. 对比切换前后同一批请求的计费结果：除修正的有损项外完全一致。
4. 再开放新能力：跨协议 Responses、Realtime、缓存与推理的分项计价。每项都要有对应的计价项、迁移与 harness 用例。
