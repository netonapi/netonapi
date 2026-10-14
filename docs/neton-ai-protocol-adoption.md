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

**现状（已核对代码）**：两个方向都会丢签名。`GeminiToOpenAiResponse` 拆分文本、推理和工具调用时不保留签名；`OpenAiToGeminiRequest` 重新构造工具调用时也不写回签名。同协议透传不受影响。

**影响（官方文档已写明）**：Google 的 [Generate Content thought signatures](https://ai.google.dev/gemini-api/docs/generate-content/thought-signatures) 规定，Gemini 3 及之后的型号在当前轮里，任何一步的第一个 `functionCall` 缺签名都会返回 400。所以客户端用 OpenAI、Anthropic 或 Responses 格式、上游是 Gemini 3 时，多轮工具调用会失败。修复前仍要用真实上游复现一次，记录实际返回，作为回归基线。

**gateway 的策略**（gateway 是框架规格 §4.5 里的"宿主"）：

1. **OpenAI 格式客户端**：在响应的每个 tool call 输出 `extra_content.google.thought_signature`（官方约定），下一轮从客户端请求中原样取回。默认开启。
2. **所有格式客户端：状态引用嵌入工具调用 ID**（框架规格 §4.5.1 机制 B）：
   - 收到 Gemini 响应时，为每个带签名的 `functionCall` 生成**安全随机、不可猜测**的状态引用，替换返回给客户端的工具调用 ID（例如 `call_<引用>`），再把签名存入 Redis。
   - 记录绑定以下信息，取回时全部校验，任一不符即视为缺失：令牌、所属用户与租户；渠道、API 类型、上游模型（及允许复用的模型范围）；原始响应 ID、步骤、内容块位置、原始调用 ID；**函数名与参数的摘要**（参数被改动后不套用旧签名）。
   - 设置过期时间与容量上限，多实例共享，用户之间隔离。
   - 下一轮请求里，从 assistant 历史的工具调用 ID 和工具结果的调用 ID 中识别状态引用，取回签名，同时把 ID 还原成上游原始的调用 ID 再发往 Gemini。
   - 每种客户端协议对工具调用 ID 有字符集和长度要求，替换后的 ID 必须满足，由编解码器测试覆盖。
   - 不把签名塞进 Anthropic thinking 的 `signature` 或 Responses 的 `encrypted_content`：这些是其他厂商的原生字段，会被它们校验。
3. **文本签名**：没有可靠的回传位置，丢弃并记 `warning`。官方明确它不是必需的，只影响效果。
4. **占位兼容 `geminiSignatureBypass`**（有损，官方强烈不鼓励）：
   - **产品默认倾向开启**，但只有存在**通过的验证记录**的"接口 + 模型范围 + 渠道"组合才实际生效。没有记录时不补占位值，缺签名的请求按渠道的损耗策略处理（`safe` 直接拒绝并返回明确错误；`allow` 透传给上游，由上游返回错误）。
   - 只写到每一步缺少真实签名的第一个 `functionCall`，不写文本部分。
   - 生效时把 `gemini_thought_signature_bypassed` 加入该渠道 `safe` 策略的已授权降级列表；管理台请求日志显示这条诊断，客服能看出这次请求用了占位签名。
   - **验证记录**：由渠道测速任务（`gateway-channel-probe`）对 Gemini 渠道做一次"带占位签名的两轮工具调用"探测，成功则写入"渠道、模型范围、验证时间、结果"，并按周期复验，失败即失效。需要新增 SQL 迁移来存储验证记录。
5. **`functionResponse.id` 暂不删除**：等"同名工具并行调用、结果乱序"的用例证明调用与结果仍能正确对应后，再决定是否删除。
6. **实施顺序**：不等框架迁移，先在现有编解码器里实现第 1、2、4 条；框架迁移完成后改由框架接管协议部分，gateway 保留状态存储、验证记录和渠道配置。

**验证**（harness，均使用真实 Gemini 上游）：
- 修复前：复现"OpenAI 格式客户端 → Gemini 3 → 两轮工具调用"，记录上游的实际返回；
- 修复后：非流式、流式、并行工具、多步工具、下一轮完整往返；三种客户端格式下通过状态引用恢复真实签名；参数被改动、令牌或用户不符、过期时都不套用旧签名；用户隔离；验证记录缺失时不补占位值；
- 断言上游接受请求、多轮结果正确，不能只断言 JSON 里出现了某个字符串。

## 5. 迁移步骤与回归

1. 框架完成第 1–3 步（框架规格 §12）。
2. gateway 在一个分支上切换到框架协议层，**行为等价**：现有编解码、SigV4、event-stream、multipart、计价与预留、路由、限流的单测全部通过；`NewGate/harness/run.sh` 端到端测试全部通过。
3. 对比切换前后同一批请求的计费结果：除修正的有损项外完全一致。
4. 再开放新能力：跨协议 Responses、Realtime、缓存与推理的分项计价。每项都要有对应的计价项、迁移与 harness 用例。
