# module-gateway SPEC

> NewGate LLM 中转站领域模块。设计依据：`NewGate/docs/superpowers/specs/2026-07-21-newgate-design.md`

## 资源清单（8 表）

| 表 | 语义 |
|----|------|
| `gateway_channels` | 上游渠道（type: openai_compatible/anthropic/gemini/azure_openai；priority 分层 + weight 加权；cost_discount 渠道成本折扣） |
| `gateway_channel_keys` | 渠道多 Key，逐 Key 禁用（status 0 手动 / 2 自动），LRU 轮转选择 |
| `gateway_tokens` | 用户 sk- 令牌：key_hash=SHA-256、key_display 前7+后4、明文仅创建响应一次；预算/过期/模型白名单/IP 白名单/分组覆盖 |
| `gateway_model_prices` | 模型定价：官方价（USD/1M，DECIMAL 字符串）+ sale_override 售价覆盖 + per_request_price 按次 |
| `gateway_groups` | 用户分组倍率 |
| `gateway_quota_accounts` | 额度账户（μUSD BIGINT），version 乐观锁 |
| `gateway_quota_transactions` | 台账（只增）：grant/consume/refund/redeem，amount 有符号 |
| `gateway_usage_logs` | 逐请求日志：tokens 明细 + charged(收入)/cost(成本) 双记 |

## 纪律

- 金额一律 μUSD（1e-6 USD）BIGINT；价格 DECIMAL 字符串（USD/1M tokens）
- 渠道 Key / 令牌明文不入日志不出接口（ChannelKeyVO/TokenVO 只给掩码）
- 台账不变量：任意成功路径 balance 变动 = transactions 求和；consume 走 version 条件更新（冲突重试 3 次）
- 权限码：`gateway:{channel,token,price,group,quota,log}:{list,get,create,update,delete,grant}`

## 路由组

- admin API：`controller.admin.*` → `/admin/gateway/*`
- 协议面：`controller.gateway.*` → gateway 组挂根（routing.conf mount="/"），认证 = GatewayTokenAuthenticator
  （`Authorization: Bearer sk-xxx`，惰性解析 TokenLogic——security 配置阶段早于 @Logic 装配）
- gateway 组响应不走 envelope：controller 注入 `HttpResponse` 直写

## 模块边界

- member：userId 的来源（会员体系）；本模块不管注册登录
- payment：充值支付成功后调 `QuotaLogic.grant(userId, amount, ref=订单号)`
- M1b（中转引擎）消费的接口：`TokenLogic.verify(plaintext)`、`ChannelLogic.pickKey(channelId)`、
  `QuotaLogic.consume(userId, amount, ref)`、`UsageLogTable.insert`

## 已知边界/待办

- SecureRandom：v1 令牌用 kotlin.random，安全强化列 M5
- 价源同步（litellm/openrouter）与变价审计表：M3
- 兑换码：M4

## 中转引擎（M1b 已落地）

- 入站：`POST /v1/chat/completions`、`POST /v1/embeddings`（gateway 组，TokenGuard 后）
- 管线：预检（令牌预算/余额）→ RequestRewriter（JsonObject 保真：只动 model/param_override/include_usage）→
  RelayRouter（priority 分层 + weight 加权随机 + 重试排除）→ NetonHttpClient（渠道 client 按配置键缓存）→
  非流式透传 / SSE 直通（旁路 SseUsageAccumulator）→ PricingCalc（μUSD）→ QuotaLogic.consume + UsageLog
- 重试：仅首字节前（连接错/超时/429/5xx），上限 min(3, 候选数)，同序列不重复渠道；开流后中断按 partial 计费收尾
- 错误：OpenAI error 格式；429 insufficient_quota（对齐 OpenAI 语义，HttpStatus 无 402）/ 503 no_available_channel /
  上游 4xx body 语义透传（擦除上游 headers）；message 经 JsonPrimitive 转义
- 计费：以上游 usage 为准（流式靠 include_usage 尾包）；无 usage 记 partial 计零（M3 收紧为估算）；
  未定价模型放行计零并 warn（M3 加兜底策略配置）
- E2E 已验证（python 假上游）：模型映射/未知字段保真/SSE 逐块直通/[DONE] 透传/charged=7500·cost=4500 精确命中/
  台账关联 usage:id/上游宕机 503/余额耗尽 429

## 存量问题（框架级待办）

- **P0 驱动可靠性缺陷（sqlx4k 1.12.0）**：PostgreSQL 参数化查询走二进制协议，NUMERIC 无 BigDecimal
  分派、未支持类型落到 `get_unchecked::<&str>`，解码失败在 Rust 侧 fetchAll 阶段 panic（release
  `panic=abort`）直接终止进程——发生在结果返回 Kotlin 之前，Kotlin 侧 catch/converter 均无效。
  **范围性规则（非永久禁令）**：在 sqlx4k 修复前，Neton 1.x 参数化查询不得直接返回 NUMERIC/DECIMAL 列；
  业务用整数最小单位、VARCHAR，或自定义 SQL 显式 `::text`/`::bigint`。
  上游修复应含：NUMERIC→BigDecimal→String 类型分派 + 行解码 Result 化经 FFI 返回（issue 草稿见
  NewGate/docs/issues/sqlx4k-numeric-decode-p0.md）
- 迁移运行时仅支持单方言 embed（postgresql）；mysql/sqlite 脚本已备，运行时按 driver 选方言是框架待办

## M2 Slice 1：Claude /v1/messages 同协议透传（已落地）

- `ProtocolAdapter` 抽象：认证头/请求改写/usage 提取/流式旁路/error 体按协议分派；RelayEngine 不散落 if(type)
- OpenAiAdapter（Bearer/include_usage 注入/OpenAI usage）、AnthropicAdapter（x-api-key+anthropic-version/不注入 stream_options/message_start+message_delta usage/上游 /v1/messages）
- 渠道类型→适配器：openai_compatible|azure_openai→openai，anthropic→anthropic
- Slice 1 仅同协议（inbound.id==channel adapter.id）；跨协议返回 503 明确降级（等 Slice 2 IR 转换）
- E2E 验证：Claude 非流式透传 + x-api-key 头正确（auth_ok=true）+ 计费 7500/4500；流式 message_start/delta usage 入账；OpenAI 入站→anthropic 渠道 503 cross-protocol

## M2 Slice 2：Claude ↔ OpenAI 跨协议直译（已落地）

- spec 决策 A：codec 在产品侧 `logic/protocol/codec/`，JSON→JSON 直译不经 IR
- 三方向纯函数：AnthropicToOpenAiRequest（system/content 块/tool_use/tool_result/image/tools/tool_choice/stop_sequences）、
  OpenAiToAnthropicResponse（非流式，finish_reason→stop_reason、usage 换名、tool_calls→tool_use）、
  OpenAiToAnthropicStream（状态机：OpenAI chunk → message_start/content_block_*/message_delta/message_stop 合法序列）
- RelayEngine.buildTransform 统一同协议(恒等)/跨协议(codec)：inbound=anthropic ∧ channel=openai 启用；usage 始终从上游原生响应提取用于计费
- 跨协议流式注入 include_usage（OpenAI 上游尾包）
- E2E 验证：Anthropic 请求 → OpenAI 假上游 → 客户端收到 Anthropic 格式响应（非流式）/ Anthropic 事件序列（流式）；计费按 OpenAI usage 7500/4500；日志 requestModel=claude 名 upstreamModel=gpt 名
- 覆盖：text + tool_use/tool_result + image；未覆盖块（thinking 等）v1 丢弃

## M2 反向补全：OpenAI ↔ Anthropic 矩阵闭合

- 反向三 codec：OpenAiToAnthropicRequest（system→system 字段、tool_calls→tool_use 块、tool→tool_result、max_tokens 缺省 4096）、
  AnthropicToOpenAiResponse（非流式，stop_reason→finish_reason、tool_use→tool_calls、usage 换名）、
  AnthropicToOpenAiStream（状态机：Anthropic 事件 → OpenAI chunk + [DONE]）
- buildTransform 新增 inbound=openai ∧ channel=anthropic 分支
- E2E：OpenAI 请求→Anthropic 假上游→OpenAI 响应（非流式+流式 chunk 序列），计费按 Anthropic usage 7500/4500
- **协议矩阵闭合**：{OpenAI,Anthropic}² 四格全通（透传×2 + codec×2）

## M2 Slice 3：Gemini 接入 + OpenAI 枢纽重构

- **架构重构**：OpenAI ChatCompletions 为内部枢纽。跨协议 = inbound→hub→upstream 组合（ProtocolCodecs），
  openai 端各段恒等；三协议只需 2×N 组转换，Gemini↔Anthropic 经 hub 免费组合。现有 4 格逐格等价保持。
- Gemini↔OpenAI 六向 codec（req/resp/stream 各双向）全单测通过；GeminiAdapter（x-goog-api-key/usageMetadata）
- RelayTransform 携带 upstreamPath（Gemini 上游 /v1beta/models/{model}:{action}）；ChainedStreamTransformer 组合双跳流式
- GeminiRelayController：`@Post("/{modelAction}")` 直接用绑定的 modelAction split 冒号，注入 model+stream 交引擎
- **3×3 协议矩阵全通（9/9）**：OpenAI/Anthropic/Gemini 三入站 × 三上游全部 E2E 验证（非流式 + 流式 + 计费），
  含经 hub 免费组合的 Gemini↔Anthropic 双跳。embeddings 目前仅 openai 透传。
- （历史勘误：曾误将 Gemini 入站失败归为「框架 URI 损坏/sqlx4k 堆损坏」，实为测试脚本 zsh
  `$m:generateContent` 参数修饰符展开把 URL 改坏；服务端从无此问题。用 `${m}:...` 即正常。）
