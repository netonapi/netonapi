# V004：durable settlement（reserve/finalize + 幂等 + 崩溃恢复）设计

> **状态**：设计稿，迁移未创建。按 Codex 硬约束「创建迁移前先写状态转换矩阵和崩溃点测试表」，
> 本文是 V004 SQL 的前置门。**迁移一旦应用到保留的 PG/MySQL 数据库即冻结**，后续只能新增 V005。

## 1. 要解决的问题（当前 P0）

| 问题 | 现状 | 后果 |
|---|---|---|
| 结算非幂等 | `ref = usage:$logId`，logId 每次重放都变 | 重放重复扣费；或跳余额却仍加 quotaUsed，破坏四项不变量 |
| 结算失败丢收入 | 非流式先返 200 再 bill；流式已提交 | DB 断连/死锁/5s 超时 → 已服务但无账 |
| budget 非硬 cap | 仅请求前软预检 | 并发穿透，超支量 = 全部在途请求成本 |

共同根因：**没有请求前的持久化资金占位**。V004 引入 settlement 表，同时充当账务记录与 durable queue。

## 2. 前置依赖（阻塞项）

**框架缺 CSPRNG**。`kotlin.random.Random` 非密码学安全，而 settlementId 必须不可预测（否则可被猜测并伪造幂等命中）。
现状 `TokenLogic.kt:62` 生成 `sk-` token 也用 `kotlin.random.Random`——同一缺口，属安全批次 P0。

**方案**：neton-security 增加 `SecureRandom.bytes(n): ByteArray`（Kotlin/Native：Darwin 用 `arc4random_buf`，
Linux 读 `/dev/urandom`；expect/actual 分平台）。V004 与 token 生成共用。**此项须先于 V004 落地。**

## 3. 双 ID 设计

| ID | 生成方 | 作用 | 存储 |
|---|---|---|---|
| `settlement_id` | 网关，CSPRNG 128-bit | 代表**一次真实上游执行**；贯穿 reserve/finalize/重试/worker 重放 | `CHAR(32)` hex，唯一索引 |
| `client_idem_key` | 客户端（可选，`Idempotency-Key` 头） | 把客户端**重复请求**映射到已有 settlement | SHA-256 `CHAR(64)`，非原文 |
| `request_fingerprint` | 网关 | 同 key 的请求一致性校验 | SHA-256 `CHAR(64)` |

**settlementId 是账务主键，客户端 key 只是外部映射键**——客户端 key 不可直接作账务主键（可被伪造/碰撞/超长）。

外部 key 与 fingerprint 一律存**定长 SHA-256 hex**，不存任意客户端字符串（避 MySQL 复合索引长度上限，且不把原文写进索引与日志）。

`request_fingerprint` 覆盖：`method | path | 原始 body | 影响响应的关键 header`（如 `Accept`、协议相关认证载体除外）。

### 客户端重复请求语义

| 情况 | 行为 |
|---|---|
| 无 `Idempotency-Key` | 每次新 settlementId；内部 finalize/worker 仍幂等，但不对客户端重发去重 |
| 同 key + 同 fingerprint + 原请求执行中 | `409`（或 `425`）+ `Retry-After`，**不重复调上游** |
| 同 key + 同 fingerprint + 非流式已完成 | 当前：`409`。响应缓存（受大小限制）为后续增强——**未实现前不得声称兼容 Stripe/OpenAI 式响应重放** |
| 同 key + 同 fingerprint + 流式已完成 | `409`，不重放（不缓存整个流） |
| 同 key + **不同** fingerprint | `409 idempotency_key_reuse`，不执行 |

## 4. 表结构

```
gateway_settlements
  id                  BIGSERIAL/BIGINT AUTO_INCREMENT  PK        -- 内部数值主键
  settlement_id       CHAR(32)   NOT NULL  UNIQUE                -- CSPRNG hex
  client_idem_key     CHAR(64)   NULL                            -- SHA-256(客户端 key)
  request_fingerprint CHAR(64)   NULL                            -- SHA-256(请求摘要)
  user_id             BIGINT     NOT NULL
  token_id            BIGINT     NOT NULL
  channel_id          BIGINT     NULL                            -- reserve 时未知，UPSTREAM_STARTED 时写入
  request_model       VARCHAR(128) NOT NULL
  endpoint            VARCHAR(128) NOT NULL
  status              VARCHAR(24) NOT NULL                       -- 状态机，见 §5
  reserved_amount     BIGINT     NOT NULL DEFAULT 0              -- μUSD
  charged             BIGINT     NOT NULL DEFAULT 0
  cost                BIGINT     NOT NULL DEFAULT 0
  pricing_snapshot    TEXT       NOT NULL                        -- 定价快照 JSON（价格/ratio/discount/saleOverride）
  usage_json          TEXT       NULL                            -- 最终 usage
  estimated           INT        NOT NULL DEFAULT 0              -- 1=输入 token 为保守估算
  terminal_seen       INT        NOT NULL DEFAULT 0
  outcome             VARCHAR(16) NULL                           -- ok/partial/error
  attempts            INT        NOT NULL DEFAULT 0
  next_retry_at       BIGINT     NOT NULL DEFAULT 0
  lease_until         BIGINT     NOT NULL DEFAULT 0              -- worker 租约
  last_error          VARCHAR(500) NULL
  created_at, updated_at BIGINT

  UNIQUE (user_id, token_id, endpoint, client_idem_key)          -- 外部幂等映射（client_idem_key 非空时）
  INDEX  (status, next_retry_at, lease_until)                    -- worker 扫描
```

配套列（reserve 双记）：

```
gateway_quota_accounts  += reserved_balance BIGINT NOT NULL DEFAULT 0
gateway_tokens          += quota_reserved   BIGINT NOT NULL DEFAULT 0
```

**可用余额 = balance - reserved_balance**（预检用此，而非裸 balance）。

## 5. 状态转换矩阵

```
RESERVED ──► UPSTREAM_STARTED ──► FINALIZE_PENDING ──► FINALIZED
   │                                     │
   │                                     └──► FAILED_RETRYABLE ──► (重试) FINALIZE_PENDING
   │                                                    └──► MANUAL_REVIEW（超重试上限）
   └──► RELEASE_PENDING ──► RELEASED
```

每个转换都是**带旧状态条件的 CAS**；`affected=0` 必须区分三种情形，不可一律当失败：

| # | 转换 | CAS 条件 | 触发点 | affected=0 的含义 |
|---|---|---|---|---|
| T1 | ∅ → RESERVED | INSERT（唯一键冲突=已存在） | 请求前 | 外部 key 命中已有 settlement → 走 §3 重复语义 |
| T2 | RESERVED → UPSTREAM_STARTED | `status='RESERVED'` | 首次向上游发请求前 | 已 started（重复调用）/ 已释放 |
| T3 | UPSTREAM_STARTED → FINALIZE_PENDING | `status='UPSTREAM_STARTED'` | 拿到 usage/结束，写入 payload | 已 FINALIZE_PENDING 或已 FINALIZED |
| T4 | FINALIZE_PENDING → FINALIZED | `status='FINALIZE_PENDING'` | 扣款+台账+日志+quotaUsed 同事务 | **AlreadyFinalized**（幂等成功，非错误） |
| T5 | RESERVED → RELEASE_PENDING | `status='RESERVED'` | 首字节前失败/取消/TTL 到期 | 已 started（**不可退**，见 C2）/ 已释放 |
| T6 | RELEASE_PENDING → RELEASED | `status='RELEASE_PENDING'` | 释放预留 | 已释放（幂等成功） |
| T7 | FINALIZE_PENDING → FAILED_RETRYABLE | `status='FINALIZE_PENDING'` | DB 死锁/断连/超时 | 竞争者已推进 |
| T8 | FAILED_RETRYABLE → FINALIZE_PENDING | `status='FAILED_RETRYABLE' AND next_retry_at<=now` | worker 取件 | 他人已取（租约竞争）|
| T9 | FAILED_RETRYABLE → MANUAL_REVIEW | `attempts >= MAX` | worker | — |

**不存在的转换一律拒绝**（如 RELEASED → FINALIZED、FINALIZED → 任何）。
`affected=0` 时重读当前状态：若已是目标状态或其下游态 → 幂等成功；若是不相容态 → 冲突报错；查无此行 → NotFound。

## 6. 崩溃点恢复策略表

| # | 崩溃点 | 落库状态 | 资金 | 恢复策略 |
|---|---|---|---|---|
| C1 | RESERVED 后、调上游前 | RESERVED | 已预留 | TTL（`created_at + reserveTtl`）到期由 worker → RELEASE_PENDING → RELEASED |
| C2 | 调上游后（可能已产生费用） | UPSTREAM_STARTED | 已预留 | **不可自动当"未调用"退款**。TTL 到期 → MANUAL_REVIEW；保留预留，保守处理 |
| C3 | 拿到 usage、finalize DB 失败 | FINALIZE_PENDING（payload 已存） | 已预留 | worker 重放 T4，用已存 payload，幂等 |
| C4 | finalize 提交成功但响应丢失 | FINALIZED | 已结算 | 重试命中 T4 affected=0 → AlreadyFinalized，直接返回 |
| C5 | 无 usage 的 partial | FINALIZE_PENDING | 已预留 | 按次价格直接结算；token 计价用估算策略并标 `estimated=1` |
| C6 | token 中途删除 | 不受影响 | — | **不得删除 settlement/账户审计记录**；quotaUsed 跳过并 warn（现有语义） |
| C7 | worker 进程崩溃 | 任意 pending | — | `lease_until` 过期后其他 worker 接管 |

**请求前无法创建并提交 RESERVED → 拒绝调用上游**（不做无保障的免费服务）。

## 7. reserve 金额计算（定价快照 + 保守上限）

| 计价模式 | 预留额 |
|---|---|
| 按次（`per_request_price`） | `perRequestPrice × groupRatio`（× saleOverride） |
| 按 token | `输入上界 × inputPrice + 输出上限 × outputPrice`，含 cache 项、groupRatio、saleOverride |

- **输出上限**：请求的 `max_tokens` / `max_output_tokens`；未传时用**模型目录的 `default_max_output_tokens`**（不用模糊的分组默认值）。
- **输入上界**：有 tokenizer 用 tokenizer；无则以请求 **UTF-8 字节数**作保守上界，并置 `estimated=1`。
- **拒绝而非免费放行**：无价格 / 无法计算安全上限 / 缺 `default_max_output_tokens` 配置 → 拒绝请求。

`pricing_snapshot` 在 reserve 时固化，finalize 必须用快照计价（避免中途改价导致预留与结算不一致）。

## 8. reserved 不变量与锁序

**固定锁序：token → account → settlement**（所有路径一致，避免自造死锁）。

reserve 事务同时：`token.quota_reserved += R`、`account.reserved_balance += R`、`settlement.reserved_amount = R`（+ 快照/摘要）。

finalize 事务同时：释放预留（两处 `-= R`）、按实际 `charged` 扣 `balance`、写台账/usage_log/quotaUsed、状态 → FINALIZED。

| finalize 情形 | 处理 |
|---|---|
| 实际 < 预留 | 释放差额 |
| 实际 = 预留 | 直接完成 |
| 实际 > 预留（理论不应发生） | 标 `OVERAGE`、**按实际入账**、阻止该 token 后续请求、告警；**不得静默截断收费** |
| 取消 / 首字节前失败 | 释放全部预留（T5→T6） |
| midstream partial | 按已知 usage / 按次价格结算 |

**终态不变量**：任一 settlement 到达 FINALIZED 或 RELEASED 后，其对 `quota_reserved` 与 `reserved_balance` 的贡献必须为 **0**。
所有释放/结算路径测试都断言 reserved 归零。

## 9. SettlementWorker

- gateway 只提供 **`SettlementWorker.tick(): TickResult`**（纯粹一次扫描+推进），**不耦合 infra 模块**；由 application/infra 调度器装配周期调用。
- 取件：`status IN (FINALIZE_PENDING, FAILED_RETRYABLE, RESERVED-TTL过期) AND next_retry_at<=now AND lease_until<=now`，
  用 `FOR UPDATE SKIP LOCKED`（PG 与 MySQL 8 均支持），CAS 写 `lease_until = now + leaseMs`、`attempts += 1`。
- 重试分类（**有界退避 + jitter**）：PostgreSQL `40001`（serialization_failure）、`40P01`（deadlock_detected）；MySQL `1213`（deadlock）、`1205`（lock wait timeout）。
- 超 `MAX_ATTEMPTS` → MANUAL_REVIEW（不再自动重试，等人工）。

## 10. 测试矩阵（真实 S8，PG/MySQL 双库并行）

**从第一个状态机测试起就双库跑**，不是最后才补 MySQL。

| # | 场景 | 断言 |
|---|---|---|
| A1 | 同 settlementId 串行 finalize ×2 | 只扣一次；第二次 AlreadyFinalized；四项一致 |
| A2 | 同 settlementId **并发** finalize ×2 | 同上；无重复台账 |
| A3 | finalize 第 1/2/3/4 写点分别注入失败 | 整事务回滚；状态留 FINALIZE_PENDING；worker 重放后四项一致 |
| A4 | finalize 成功但响应丢失后重试 | AlreadyFinalized；金额不变 |
| A5 | worker 与在线 finalize 竞争 | 恰好一方成功；无双扣；租约生效 |
| A6 | reserve 后未调上游（TTL） | 自动 RELEASED；reserved 归零；balance 不变 |
| A7 | UPSTREAM_STARTED 后崩溃 | **不自动退款**；进 MANUAL_REVIEW；预留保留 |
| A8 | 同 client key + 不同 body | `409`；不执行上游；无新 settlement |
| A9 | PG 死锁/序列化失败注入 | 按 40001/40P01 重试并最终成功 |
| A10 | MySQL 死锁/锁等待超时注入 | 按 1213/1205 重试并最终成功 |
| A11 | 全部终态 | `quota_reserved` 与 `reserved_balance` 归零；Σcharged==Σ台账==balanceΔ==quotaUsed |
| A12 | 无价/无上限配置 | 拒绝请求；不调上游；无 settlement 残留 |

## 11. 实施顺序

1. **前置**：neton-security `SecureRandom`（框架，独立提交）。
2. V004 迁移 **PG + MySQL 同批**（含 `reserved_balance`/`quota_reserved`/`gateway_settlements`）。
3. 状态机 + CAS 转换层（纯逻辑 + 双库测试）。
4. reserve/finalize 接入 RelayEngine（替换现 bill 路径）。
5. SettlementWorker.tick() + 调度装配。
6. 真实 S8（A1–A12）双库；SPEC 状态更新。

**任何一项没有「实现 + PG 测试 + MySQL 测试 + 失败路径测试 + SPEC 更新」都不得标记完成。**
