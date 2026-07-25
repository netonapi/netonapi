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
- **数据库支持范围：仅 PostgreSQL 与 MySQL（不含 SQLite）**。sqlx4k 的 Native 驱动编译期单选、不能由同一
  二进制运行时切换，故定义为两种构建产物：`-Pneton.database.driver=postgres`（默认）与 `=mysql`，分别发布
  二进制/镜像标签，CI 对两者跑同一组可靠性测试。gateway 迁移嵌入方言已跟随 `neton.database.driver`
  （postgres→postgresql / mysql→mysql，见 build.gradle.kts）。启动应校验配置库类型与编译驱动一致、不一致即退出（待办）。
  **当前状态**：PostgreSQL 已端到端验证（harness 13/13）；MySQL **尚不可运行**，见下方实测结论。
  迁移引擎的 search_path 复位为 PostgreSQL 专属（MySQL 分支不发）。

- **⚠️ MySQL 可运行性实测（2026-07，首次真实执行）**：mysql 构建产物**可链接**（`-Pneton.database.driver=mysql`
  linkDebugExecutable 成功），gateway 自身的 MySQL 脚本**已修好并跑通**（V001 八表 + V003 唯一索引/cooldown，
  含与 PG 对等的重复 ref 预检；此前 TEXT 带 DEFAULT 被 MySQL 拒绝，从未执行成功过）。
  **但整机迁移仍失败**：NewGate 依赖的上游模块 **infra(6)/member(11)/payment(12)/platform(2) 共 31 个迁移脚本
  只有 PostgreSQL 版本、无 MySQL 版本**，其 build.gradle 亦硬编码 `dialects = ["postgresql"]`，
  在 mysql 构建下被整体跳过 → gateway V002 因 `system_menus` 不存在而失败。
  **结论**：MySQL 支持不是 gateway 单模块问题，前置是**上游 4 个模块 31 个脚本的 MySQL 移植 + 方言派生**
  （范围在 neton-application 模块栈，非 NewGate 自有代码）。在此之前：
  - 不得宣称"MySQL 已支持"；
  - **V004「PG/MySQL 从第一个状态机测试起并行验证」的硬约束当前无法满足**——MySQL 侧连基础表都建不起来。
    可选路径：(a) 先移植上游 31 脚本再开 V004；(b) V004 先 PG 落地、MySQL 侧以「gateway 单模块 schema + 状态机
    单元测试」并行验证（不含跨模块 E2E），待上游移植完成再补整机 MySQL harness。此为待决策项。

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

## M3-a：渠道容错（自动禁用 + 恢复，已落地）

- FailurePolicy 纯策略：连续失败达阈值（默认 5）自动禁用 Key（status=2）；渠道无可用 Key 则挂起（status=2）
- RelayEngine 接线：429/5xx/网络/超时 + 上游 401/403 计 Key 失败；成功清零 fail_count；其它 4xx（客户端错）不计
- 管理端 `PUT /admin/gateway/channel/revive/{id}`：渠道启用 + 全部 Key 复位
- E2E：连打只回 500 的渠道 5 次 → key status=2 failCount=5、channel status=2；revive 后全复位

## M3-b：可靠性收口（Codex 两轮评审，基础路径已落地，DB 并发集成测试待入 CI）

- **流式首字节前重试**：streamWithPreflight——独立 producer 拉上游、确认首块后才提交 downstream；
  首字节前失败在 downstream 未提交时抛出可换渠道，不撞 ResponseAlreadyCommitted。producer 捕获上游异常存
  openError（不外抛避免 cancel scope 抢占重试异常）。E2E：dead→live 流式成功。
- **stream outcome**：execute 返回 SUCCESS/PARTIAL——仅完整成功清 Key failCount；中断/上游 4xx 为 PARTIAL 不改 Key
  状态；downstream 断连（CancellationException）先 bill(partial) 再抛，不丢账。
- **账务事务化 + 原子扣减**：bill() 把 usage_log + 余额扣减 + 台账 + token.quotaUsed 放**同一 dbContext().transaction**。
  余额/quotaUsed 均用 SQL 自增（`balance = balance + delta` / `quota_used = quota_used + charged`，raw execute），
  PostgreSQL 行级锁串行化并发结算——**无 version CAS 竞争、无重试丢单**（此前 5 次乐观锁重试在单账户 20 并发下会耗尽、
  抛 "bill contention" 丢账，harness S1 实测暴露：仅 9/20 计费）。debitInTx 校验 affected==1。
  不变量：**活跃 token**——Σlog.charged == Σ台账扣款 == 余额变化 == token.quotaUsed（harness S1 20 并发四者=150000）；
  **中途删除的 token**——`quota_used ... WHERE id AND deleted=0` 命中 0 行 → 账户级三项相等（log/余额/台账），quotaUsed 略过并 warn。
  settle 无条件扣减允许转负（后付费）。account() 首建幂等（INSERT ... ON CONFLICT (user_id) DO NOTHING + 重读），
  并发首请求不再撞 uk_gateway_quota_accounts_user 抛错丢账（harness S7：20 并发首建 → 账户唯一、无 500 崩溃）。
  两条自增 + 首建 raw SQL 均 PostgreSQL 专用，MySQL 侧需经真实 harness 验证命名参数/affected rows/事务回读/并发结果后方可宣布兼容。
- **溢出保护（V003，已落地）**：debitInTx 转账前 checkedAdd（BIGINT 同号相加变号即抛）。待补：边界/并发测试、MySQL SQL mode 验证。
- **ref 唯一约束（V003，部分成立）**：`gateway_quota_transactions.ref` 部分唯一索引（WHERE ref IS NOT NULL）+ debitInTx
  `ON CONFLICT (ref) ... DO NOTHING`；建索引前 DO 块校验存量无重复 ref。**⚠️ 但这不构成请求级幂等**（见下"未闭环 P0"）。
- **429 渠道 cooldown（V003，已落地，待改进）**：429 把渠道 `cooldown_until` 抬到 now+30s（GREATEST），冷却期内
  RelayRouter.candidates 排除该渠道（harness S10）。**待改进（Codex P1）**：应优先冷却具体 Key 而非整个多 Key 渠道；
  解析 `Retry-After`/reset header（30s 仅 fallback）+ jitter；所有候选均限流应返回 429+Retry-After 而非伪装 503。
- **流式 terminalSeen（完全闭环）**：StreamUsageAccumulator 增 `terminalSeen()`——OpenAI `[DONE]` 绝对终止，无 `[DONE]`
  时**所有已观察 choice index 都须 finish**（n>1 严谨）；Anthropic `message_stop`；Gemini 所有已观察 candidate 都终态 `finishReason`。
  上游出错或传输层 EOF 但未见终止标记 = 上游截断 → PARTIAL，**不调 transformer.finish()**（不伪造 [DONE]/message_stop），
  按网络失败策略计 Key 失败（首字节后不重试渠道）。harness S9：无伪造 [DONE]、status=partial、per-request 价四项一致、Key failCount≥1、在途=0、有限时间。
- **SSE 解析基础（Codex 两轮追加，已闭环）**：抽共享 `SseEventFramer`（3 累加器 + 4 流 codec 复用）——支持 `\n\n`/`\r\n\r\n`/`\r\r`、
  边界或行被拆在任意 chunk、多行 data 字段；**空 chunk 不清 pendingCr**（否则后续 \n 造假边界）。producer 用增量 `Utf8StreamDecoder`
  **严格解码**：完整前缀非法 UTF-8 或 EOF 仍有不完整尾部都抛 CharacterCodingException（绝不吐 U+FFFD）→ producer 记 openError → PARTIAL。
  终止判定按**请求期望的 candidate 数**（OpenAI `n` / Gemini `candidateCount`，默认 1）：0..n-1 全 finish（或 OpenAI `[DONE]` 绝对终止）——
  仅"已观察都 finish"无法发现 n=2 但 index 1 从未出现的截断。单测：CRLF/任意分片/空 chunk pendingCr、逐字节 UTF-8、非法 lead/孤立 continuation/
  EOF 截断 2·3-byte、期望缺失 candidate 反例、Anthropic message_stop(LF/CRLF/逐字符/缺失)、Gemini candidateCount=2、四 codec CRLF 接线。
  **框架待办（不阻塞 V004）**：Neton 自带 `Flow<NetonHttpStreamChunk>.parseSseEvents()` 仍逐 byte chunk decodeToString（同类 UTF-8 bug）；
  gateway 当前不走它，后续应把共享 framer/decoder 上提框架，避免两套 SSE 实现漂移。
- **V004 durable settlement（已闭环，harness 17/17）**：设计见 `docs/V004-durable-settlement-design.md`。
  - **资金占位先行**：请求前按定价快照算保守上界（输出取 max_tokens / 模型 `default_max_output_tokens`，输入以 UTF-8
    字节数为上界并标 estimated），在**同一事务**内写 settlement + 账户 `reserved_balance` + 令牌 `quota_reserved`；
    条件更新保证 `balance-reserved_balance >= r` 与 `quota_used+quota_reserved+r <= quota_budget`，
    affected=0 时区分余额不足/预算不足/令牌不可用（不同错误码）。**预留不成功即拒绝调上游**。
    **无定价或无法确定输出上限 → 拒绝请求**（此前免费放行是收入泄漏）。
  - **结算幂等（真闭环）**：台账 ref = `settlement:<CSPRNG settlementId>`，**键稳定**——重放插 0 行即整体 no-op，
    不再是每次都变的 `usage:$logId`。客户端 `Idempotency-Key` 仅作外部映射键（长度 1..255 校验后存 SHA-256），
    命中既有记录返回 409 且不重复调上游；fingerprint 用长度前缀编码消除拼接歧义。
  - **硬预算**：预算在 reserve 阶段条件占位，并发请求不再穿透（此前的「事后禁用 token」假 cap 已撤回）。
  - **结算失败可恢复**：`SettlementWorker.tick()`（不耦合调度，由 application 装配；另有 admin 端点供运维催办）
    两阶段取件——短事务 `FOR UPDATE SKIP LOCKED` 打租约并提交，再以固定锁序 token→account→settlement 推进，
    **不反转锁序**。重放 FINALIZE_PENDING、TTL 释放 RESERVED、TTL 将 UPSTREAM_STARTED 转 MANUAL_REVIEW
    （**不自动退款**：崩溃时不知上游是否已计费）；退避带 jitter，40001/40P01 视为可重试，超上限转人工。
  - **usage_log 原子性**：日志维度随 T3 落在 settlement 行，由 finalize 在扣款同事务内写出——既不会被 relay 与
    finalizer 双写重复计账，worker 重放也能复现同一行。
  - **能力边界（如实）**：✅ 收入有资金保障（预留先行）；⚠️ **结算自动恢复仅在 payload 已落库之后**——
    T3 落库本身失败且进程崩溃（C3′）时真实 usage 永久丢失，在线用有界 NonCancellable 重试压低概率，
    仍失败则保留预留转人工，**不按预留额自动结算**（预留是上界，自动扣会系统性超收）。
  - 框架侧：`HttpStatus` 补 402/410/413/425/504——此前 `fromCode` 返回 null 会静默回落 502，把「余额不足」
    伪装成上游故障；未映射码现在显式告警。

- **Key 状态 CAS**：recordKeyFailure/Success 改列级 CAS（重读+条件更新，并发不丢计数、不复活已禁用 Key）；
  429 限流不计永久禁用（仅 5xx/网络/超时/401/403）；reviveChannel 只复位自动禁用(status=2)、保留手动禁用(status=0)。
  E2E：5×500 触发 auto-key 禁用、manual-key 保持 0；revive 后 auto-key 复位、manual 仍 0。
- **断连结算 + producer 生命周期（Codex 三/四轮 #1）**：downstream 断连的 bill 放 withContext(NonCancellable)+
  withTimeoutOrNull(5s)（否则 cancelled 协程 bill 立即再取消丢账）。异常分类：CancellationException→取消 producer+
  NonCancellable 结算+重抛；确认下游断连（IO/Closed/Broken/pipe）→取消 producer+结算+PARTIAL；其它（codec bug 等）→
  取消 producer+结算+ERROR 日志+重抛（不伪装断连）。producer capacity=1（背压）+异常路径 chunks.cancel()（防 send() 挂起泄漏）。
  E2E：perRequestPrice=5000 大块流断连 → log.charged=5000/tx=-5000/balance_delta=5000 全一致（真实扣款，非仅走到函数）。
- **分类统一（Codex #4）**：非流式 401/403 也计 Key 失败（此前只在流式/throw 路径计）；E2E：非流式 3×403 → failCount=3。
- **Key 轮转（Codex #5）**：recordKeyFailure 也更新 lastUsedAt → LRU 轮转到其他 Key 不死磕坏 Key。
- 删死代码 consume()（无调用方且非事务化）。token 中途删除：账户级三表照提交+warn（不回滚，避免已服务请求不计费=收入泄漏，此处推回 Codex #3）。
- **可靠性 harness（已落地，本机 17/17 绿）**：`harness/run.sh`（NewGate 仓）一条命令——隔离测试数据库 +
  假上游（`fakes.py`，含 `/inflight` 在途计数、midabort 模式）+ 真实网关，10 场景 13 断言。⚠️ S8 只测 DB 唯一索引（未走真实
  bill 重放路径）、S9 只验证收块+服务存活（未断言 partial/账务/Key 状态/无伪造终止）——见上"未闭环 P0"，需以稳定 settlementId
  经测试 seam 真实重放、并断言 midstream 的 partial 语义。场景：S1 20 并发四项不变量、
  S2 dead→live 流式重试、S3 断连四项一致 + producer 拆除（在途归零）、S4 401/403 禁用 & 429 不禁用、S5 revive 保留手动禁用、
  S6 token 中途删除账户级一致 + warn、S7 首建账户并发唯一、S8 结算幂等唯一约束、S9 上游 midstream abort、S10 429 渠道 cooldown。
  迁移根因修复：payment/V001 的 `set_config('search_path','',false)` 污染 pooled 连接 → MigrationEngine 每个 PG 迁移事务
  开头 `SET search_path TO DEFAULT` 复位（会话级、提交后持久修复连接、不硬编码 public；不改任何已应用 V001 checksum）。
- **harness/CI 仍待办**（Codex 评审，紧接下一提交）：改用 PostgreSQL service/container + 动态分配应用/fake 端口（当前固定
  5432/8080/9900–9991，仅"隔离库"非"隔离实例"）；入 CI + 全局 watchdog（构建/迁移/DB 命令超时）；四个写入点分别注入异常
  验证整事务 rollback（需 DbContext 注入 seam）；DB 死锁/连接中断重试分类 + 结算失败 outbox（后台重放，需接调度器）；
  预付费 reserve/finalize。
- **迁移 search_path 复位的严格证明（待补）**：现有 contract test 仅证明复位 SQL 在脚本 DDL 前发送；fresh-DB harness
  证明全量迁移成功，但未严格证明"提交后同一物理池连接已干净"。应补 pool-size=1 的真实 PostgreSQL 集成测试：
  脚本 A 污染会话 → 脚本 B 用未限定 DDL → 迁移后复用同一连接，`SHOW search_path` 等于连接默认值且未限定查询成功。

## 待办（Codex 评审确认的真实差距，未完成）

- 原生认证载体：仅 Bearer sk-；Anthropic x-api-key / Gemini x-goog-api-key|?key= 未映射到 NewGate token
- Azure OpenAI 未实现（api-key 头/deployment URL/api-version），当前 azure_openai 走 OpenAiAdapter
- IP 白名单仅字段无校验；RPM/TPM/并发流上限未实现；ttfbTimeoutMs 未接超时
- 渠道 Key 明文存储（设计要求加密）；base/proxy URL 无 SSRF 校验
- 未定价模型/无 usage 请求免费放行（收入泄漏）；saleOverride 未参与计费；反向模型映射未执行
- codec 有损：thinking/cache_control/多模态/多 candidate/结构化输出等未覆盖；跨协议上游 4xx 未按入站协议重序列化
- /v1/models 返回空；扩展端点（Responses/Images/Audio/Rerank）未实现
- 管理台契约错位：日志页调 /gateway/log/page（后端只有 list-by-*）；多 Key/测速/健康 UI 缺；令牌页字段不全
- 3×3 = 文本聊天核心子集 baseline E2E，非完整协议兼容；E2E 脚本未入库/未入 CI
- fork 卫生：application.conf/database.conf 仍留 privchat 名/库地址/默认 JWT 占位
