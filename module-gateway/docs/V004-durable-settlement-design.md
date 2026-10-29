# V004：durable settlement（reserve/finalize + 幂等 + 崩溃恢复）设计

> **状态**：设计稿 rev3（PostgreSQL-only）。**MySQL 已退出范围**——交付走 Docker 固定 PostgreSQL，
> 用户不需要选数据库。`sql/mysql/` 冻结在 V003，不再新增。
>
> 本文是 V004 SQL 的前置门（状态转换矩阵 + 崩溃点表）。**迁移一旦应用到保留数据库即冻结**，后续只能新增 V005。

## 1. 要解决的问题（当前 P0）

| 问题 | 现状 | 后果 |
|---|---|---|
| 结算非幂等 | `ref = usage:$logId`，logId 每次重放都变 | 重放重复扣费；或跳余额却仍加 quotaUsed，破坏四项不变量 |
| 结算失败丢收入 | 非流式先返 200 再 bill；流式已提交 | DB 断连/死锁/5s 超时 → 已服务但无账 |
| budget 非硬 cap | 仅请求前软预检 | 并发穿透，超支量 = 全部在途请求成本 |

共同根因：**没有请求前的持久化资金占位**。V004 引入 settlement 表，同时充当账务记录与 durable queue。

### 能力边界（必须如实区分，不得混为一谈）

| 说法 | 成立范围 |
|---|---|
| **收入有资金保障** | ✅ RESERVED 提交后才调上游；预留在账上，不会「已服务但完全无记录」 |
| **结算可自动恢复** | ⚠️ **仅当 payload 已落库**（FINALIZE_PENDING 起）。见 §6 C3′：T3 落库失败且进程崩溃 → 真实 usage 永久丢失，只能人工处理 |

## 2. 前置依赖

- ✅ **CSPRNG**：`neton.security.random.SecureRandom`（cryptography-kotlin，Native 走平台 CSPRNG）已落地。
- ✅ **SHA-256**：`neton.security.digest.Sha256.hex`。

## 3. 双 ID 与幂等输入契约

| ID | 生成方 | 作用 | 存储 |
|---|---|---|---|
| `settlement_id` | 网关，CSPRNG 128-bit | 代表**一次真实上游执行**；贯穿 reserve/finalize/重试/worker | `CHAR(32)` hex，唯一索引 |
| `client_idem_key` | 客户端（可选，`Idempotency-Key` 头） | 把客户端**重复请求**映射到已有 settlement | SHA-256 `CHAR(64)`，非原文 |
| `request_fingerprint` | 网关 | 同 key 的请求一致性校验 | SHA-256 `CHAR(64)` |

**settlementId 是账务主键，客户端 key 只是外部映射键**——客户端 key 可被伪造/碰撞/超长，不可作账务主键。
外部 key 与 fingerprint 一律存**定长 SHA-256 hex**（避 MySQL 索引长度上限，且不把原文写进索引与日志）。

### 3.1 输入边界（P1 修订）

- `Idempotency-Key` 原始字节长度必须在 **1–255 bytes**；超限直接 `400 invalid_idempotency_key`（不截断、不哈希）。
- 仅在长度校验通过后做 SHA-256。

### 3.2 fingerprint 编码（P1 修订：消除歧义）

**不得用分隔符拼接**（`a|b` 与 `a` + `|b` 会得到相同原文）。使用**长度前缀编码**：

```
fp_input := LP(method) ‖ LP(path) ‖ LP(content_type) ‖ LP(canonical_headers) ‖ LP(raw_body)
LP(s)    := decimal_len(utf8(s)) ‖ ":" ‖ utf8(s)      // 长度前缀，无歧义
request_fingerprint := SHA256_hex(fp_input)
```

- `canonical_headers`：影响响应的协议头，按名称排序后各自 LP 编码（如 `Accept`、`Anthropic-Version`；
  **排除**认证载体与逐跳头）。
- `raw_body`：原始字节，不做 JSON 归一（归一会让语义不同的请求碰撞）。

### 3.3 客户端重复请求语义

| 情况 | 行为 |
|---|---|
| 无 `Idempotency-Key` | 每次新 settlementId；内部 finalize/worker 仍幂等，但不对客户端重发去重 |
| 同 key + 同 fingerprint + 原请求执行中 | `409`（或 `425`）+ `Retry-After`，**不重复调上游** |
| 同 key + 同 fingerprint + 已完成（非流式） | 当前：`409`。响应缓存（受大小限制）为后续增强——**未实现前不得声称兼容 Stripe/OpenAI 式响应重放** |
| 同 key + 同 fingerprint + 已完成（流式） | `409`，不重放（不缓存整个流） |
| 同 key + **不同** fingerprint | `409 idempotency_key_reuse`，不执行 |

## 4. 表结构

```
gateway_settlements
  id                  BIGSERIAL / BIGINT AUTO_INCREMENT  PK      -- 内部数值主键
  settlement_id       CHAR(32)   NOT NULL  UNIQUE                -- CSPRNG hex
  client_idem_key     CHAR(64)   NULL                            -- SHA-256(客户端 key)
  request_fingerprint CHAR(64)   NULL                            -- SHA-256(§3.2 LP 编码)
  user_id             BIGINT     NOT NULL
  token_id            BIGINT     NOT NULL
  channel_id          BIGINT     NULL                            -- T2 时写入
  request_model       VARCHAR(128) NOT NULL
  endpoint            VARCHAR(128) NOT NULL
  status              VARCHAR(24) NOT NULL                       -- 见 §5
  retry_action        VARCHAR(16) NULL                           -- FINALIZE / RELEASE（进 MANUAL_REVIEW 时记录当时动作）
  reserved_amount     BIGINT     NOT NULL DEFAULT 0              -- μUSD
  charged             BIGINT     NOT NULL DEFAULT 0
  cost                BIGINT     NOT NULL DEFAULT 0
  pricing_snapshot    TEXT       NOT NULL                        -- 定价快照 JSON
  usage_json          TEXT       NULL                            -- T3 落库的 payload
  estimated           INT        NOT NULL DEFAULT 0              -- 1=输入 token 为保守估算
  terminal_seen       INT        NOT NULL DEFAULT 0
  outcome             VARCHAR(16) NULL                           -- ok / partial / error / OVERAGE（§8）
  attempts            INT        NOT NULL DEFAULT 0
  next_retry_at       BIGINT     NOT NULL DEFAULT 0
  lease_owner         CHAR(32)   NULL                            -- worker 实例标识（§9 两阶段）
  lease_until         BIGINT     NOT NULL DEFAULT 0
  last_error          VARCHAR(500) NULL
  created_at, updated_at BIGINT

  UNIQUE (user_id, token_id, endpoint, client_idem_key)          -- 外部幂等映射
  INDEX  (status, next_retry_at, lease_until)                    -- worker 扫描
  INDEX  (status, created_at)                                    -- TTL 扫描（RESERVED / UPSTREAM_STARTED 超时）
```

配套列（reserve 双记）：

```
gateway_quota_accounts  += reserved_balance BIGINT NOT NULL DEFAULT 0
gateway_tokens          += quota_reserved   BIGINT NOT NULL DEFAULT 0
```

**可用余额 = balance - reserved_balance**（预检用此，而非裸 balance）。

## 5. 状态转换矩阵（P0 修订）

**取消通用 `FAILED_RETRYABLE`**：状态本身即编码重试动作，`FINALIZE_PENDING` / `RELEASE_PENDING` 就是
durable 重试态，重试元数据（`attempts` / `next_retry_at` / `last_error`）挂在同一行。

```
RESERVED ──T2──► UPSTREAM_STARTED ──T3──► FINALIZE_PENDING ──T4──► FINALIZED
   │                    │                      │ ▲                       
   │                    │                      │ └─T7 重试(留在本状态,退避)
   │                    └────T9──────────────► MANUAL_REVIEW ◄──T8(超上限)
   │                       (TTL 超时,不自动退款)      ▲
   │                                                 │ T10(超上限)
   └──T5──► RELEASE_PENDING ──T6──► RELEASED         │
                 │ ▲                                 │
                 │ └─T11 重试(留在本状态,退避)────────┘
```

| # | 转换 | CAS 条件 | 触发点 | affected=0 的含义 |
|---|---|---|---|---|
| T1 | ∅ → RESERVED | INSERT（唯一键冲突=已存在） | 请求前 | 外部 key 命中已有 settlement → 走 §3.3 |
| T2 | RESERVED → UPSTREAM_STARTED | `status='RESERVED'` | 首次向上游发请求前 | 已 started（重复调用）/ 已释放 |
| T3 | UPSTREAM_STARTED → FINALIZE_PENDING | `status='UPSTREAM_STARTED'` | 拿到 usage/流结束，**写入 usage_json** | 已 FINALIZE_PENDING 或已 FINALIZED |
| T4 | FINALIZE_PENDING → FINALIZED | `status='FINALIZE_PENDING'` AND lease 校验 | 扣款+台账+日志+quotaUsed+释放预留同事务 | **AlreadyFinalized**（幂等成功，非错误） |
| T5 | RESERVED → RELEASE_PENDING | `status='RESERVED'` | 首字节前失败/取消/TTL 到期 | 已 started（**不可退**，见 C2）/ 已释放 |
| T6 | RELEASE_PENDING → RELEASED | `status='RELEASE_PENDING'` AND lease 校验 | 释放预留（两处 `-= R`） | 已释放（幂等成功） |
| T7 | FINALIZE_PENDING → FINALIZE_PENDING | `status='FINALIZE_PENDING'` | finalize 失败：`attempts+1`、`next_retry_at=退避` | 竞争者已推进 |
| T8 | FINALIZE_PENDING → MANUAL_REVIEW | `status='FINALIZE_PENDING' AND attempts>=MAX` | worker | 竞争者已推进 |
| T9 | UPSTREAM_STARTED → MANUAL_REVIEW | `status='UPSTREAM_STARTED' AND created_at+upstreamTtl<now` | worker TTL 扫描 | 已推进到 FINALIZE_PENDING |
| T10 | RELEASE_PENDING → MANUAL_REVIEW | `status='RELEASE_PENDING' AND attempts>=MAX` | worker | 竞争者已推进 |
| T11 | RELEASE_PENDING → RELEASE_PENDING | `status='RELEASE_PENDING'` | release 失败：`attempts+1`、退避 | 竞争者已推进 |

**不存在的转换一律拒绝**（RELEASED → FINALIZED、FINALIZED → 任何、MANUAL_REVIEW → 自动态）。
`affected=0` 时重读当前状态：已是目标态或其下游态 → 幂等成功；不相容态 → 冲突报错；查无此行 → NotFound。

进入 MANUAL_REVIEW 时必须写 `retry_action`（FINALIZE / RELEASE）与 `last_error`，供人工判断当时在做什么。

## 6. 崩溃点恢复策略表（P0 修订）

| # | 崩溃点 | 落库状态 | 资金 | 恢复策略 | worker 扫描 |
|---|---|---|---|---|---|
| C1 | RESERVED 后、调上游前 | RESERVED | 已预留 | `created_at+reserveTtl` 到期 → T5 → T6 释放 | ✅ |
| C2 | 调上游后（可能已产生费用） | UPSTREAM_STARTED | 已预留 | **不可自动当"未调用"退款**；`upstreamTtl` 到期 → **T9 MANUAL_REVIEW**，保留预留 | ✅ |
| C3 | 拿到 usage、**payload 已落库**、finalize DB 失败 | FINALIZE_PENDING | 已预留 | worker 重放 T4（用已存 payload），幂等 | ✅ |
| **C3′** | 拿到 usage、**T3 落库本身失败**且进程崩溃 | UPSTREAM_STARTED | 已预留 | ⚠️ **真实 usage 永久丢失**。在线阶段先做**有界 NonCancellable 重试**（见下）；仍失败则由 C2 路径转人工。**不按预留额自动结算**（预留是上界，自动扣会系统性超收） | ✅（走 C2） |
| C4 | finalize 提交成功但响应丢失 | FINALIZED | 已结算 | 重试命中 T4 affected=0 → AlreadyFinalized | — |
| C5 | 无 usage 的 partial（上游中断但流程存活） | FINALIZE_PENDING | 已预留 | 按次价格直接结算；token 计价用估算并标 `estimated=1` | ✅ |
| C6 | token 中途删除 | 不受影响 | — | **不得删除 settlement/账户审计记录**；quotaUsed 跳过并 warn | — |
| C7 | worker 进程崩溃 | 任意 pending | — | `lease_until` 过期后其他 worker 接管 | ✅ |
| C8 | release 失败 | RELEASE_PENDING | 已预留 | T11 退避重试；超上限 → T10 MANUAL_REVIEW | ✅ |

**worker 扫描集合**（P0 修订，须与本表一致）：
`FINALIZE_PENDING` ∪ `RELEASE_PENDING` ∪ `RESERVED`(TTL 超时) ∪ `UPSTREAM_STARTED`(TTL 超时)。

**T3 在线重试**：上游结束后写 `usage_json + FINALIZE_PENDING` 用 `withContext(NonCancellable)` +
有界重试（N 次，短退避，总时限内）——这是把 C3′ 概率压到最低的唯一在线手段；不得声称它消除了 C3′。

**请求前无法创建并提交 RESERVED → 拒绝调用上游**（不做无保障的免费服务）。

## 7. reserve 金额与原子条件（P0 修订）

### 7.1 金额计算（双轨）

计价分两条轨道，混用即无法核算毛利：

| 轨道 | 公式 | 用途 |
|---|---|---|
| **售价轨**（收入） | `sale_override` 逐字段覆盖；缺省字段 = 官方价 × 全局加价率 `NETONAPI_SALE_MARKUP` | reserve 金额、`charged`（再 × 分组倍率） |
| **成本轨** | 官方价 × 渠道折扣 `cost_discount` | `cost`（只用于毛利核算，不进用户账务） |

`gateway_model_prices` 存的一律是**官方价**（成本轨基准）；售价由 `sale_override` 或全局加价率推导。

| 计价模式 | 预留额 |
|---|---|
| 按次（`per_request_price`） | `售价 perRequest × groupRatio` |
| 按 token | `输入上界 × 售价 inputPrice + 输出上限 × 售价 outputPrice`（含 cache 项）`× groupRatio` |

- **输出上限**：请求的 `max_tokens` / `max_output_tokens`；未传时用**模型目录的 `default_max_output_tokens`**。
- **输入上界**：有 tokenizer 用 tokenizer；无则以请求 **UTF-8 字节数**作保守上界，并置 `estimated=1`。
- **拒绝而非免费放行**：无价格 / 无法计算安全上限 / 缺 `default_max_output_tokens` → 拒绝请求。
- **坏价 fail-fast**：官方价、`sale_override`、加价率任一非法 → 入口直接 `500 model_pricing_invalid`，
  **不建 settlement、不预留、不调上游**。用 500 而非 400：请求本身没问题，是运营侧定价数据坏了，
  归成客户端错误会掩盖事故。
- **发布闸门（毛利底线）**：admin 写价时 `SalePricing.requireMargin` 校验售价每一维
  ≥ 官方成本 × `NETONAPI_MIN_MARGIN`（默认 `1`，即不得低于官方成本），违者 400 拒绝发布。

#### 7.1.1 `pricing_snapshot`（v2 双轨）

reserve 时固化售价轨与官方轨；选定/切换上游渠道时并入渠道折扣（见 `markUpstreamStarted`，
重试换渠道必须刷新——否则成本记在没打过的那家头上）：

```json
{"sale":{"input":"5","output":"20","cacheRead":"0","cacheWrite":"0","perRequest":null},
 "ratio":"1",
 "cost":{"input":"2.5","output":"10","cacheRead":"0","cacheWrite":"0","perRequest":null},
 "costDiscount":"0.8"}
```

- finalize **必须且只能**用快照计价：`charged = sale × ratio`、`cost = cost × costDiscount`。
  中途改价、改折扣、改倍率只影响后续请求（worker 重放同样按快照，不按当前价）。
- **严格校验、无回退**：JSON 非法、任一轨字段缺失、数值越界、`ratio`/`costDiscount` 不在 `(0,100]`
  → 一律视为快照损坏：**保留预留资金**、转 `MANUAL_REVIEW`、打 CRITICAL 告警。
  绝不回退实时价（那会重新制造「旧价预留、新价扣款」），也绝不用 `"0"`/`"1.0"` 默认值静默补齐。
- **毛利倒挂**（`cost > charged`）：折扣 >1 的渠道或倍率 <1 的用户组仍可能压穿成本，
  记 `margin inversion` 告警（发布闸门挡的是配错价，不是经营策略）。
- **升级兼容**：v1 单轨快照（`{"input":...}`）不被 v2 解析器接受 → 升级时在途老记录会转人工。
  发布前排空在途请求即可避免。

### 7.2 原子预留条件（必须由数据库条件更新保证，不得应用层先读后写）

```sql
-- ① 账户预留：可用余额足够
UPDATE gateway_quota_accounts
   SET reserved_balance = reserved_balance + :r, version = version + 1
 WHERE user_id = :uid
   AND balance - reserved_balance >= :r;

-- ② token 预留：预算足够（无预算则不限）
UPDATE gateway_tokens
   SET quota_reserved = quota_reserved + :r
 WHERE id = :tid AND deleted = 0
   AND (quota_budget IS NULL
        OR quota_used + quota_reserved + :r <= quota_budget);
```

- ①② 与 settlement INSERT **同一事务**，任一 affected=0 即整体回滚（无残留预留、无孤儿 settlement）。
- **affected=0 的归因**：回滚后重读该行区分 —— 余额不足（`balance-reserved < r`）/ 预算不足
  （`quota_used+quota_reserved+r > budget`）/ 行不存在或已删除。三者返回不同错误码，不可笼统报"冲突"。
- **BIGINT checked arithmetic**：`r`、`quota_used+quota_reserved+r`、`reserved_balance+r` 均做溢出检查
  （同号相加变号即抛），避免溢出绕过条件。
- **首建账户并发**：沿用现有 `INSERT ... ON CONFLICT (user_id) DO NOTHING` + 重读（MySQL：`INSERT IGNORE`），
  在 ① 之前保证账户存在。

## 8. 锁序、finalize 语义与 reserved 不变量

**固定锁序：token → account → settlement**（**所有路径**一致，含 worker，见 §9）。

finalize 事务同时：释放预留（`quota_reserved -= R`、`reserved_balance -= R`）、按实际 `charged` 扣 `balance`、
写台账/usage_log/quotaUsed、状态 → FINALIZED。

| finalize 情形 | 处理 |
|---|---|
| 实际 < 预留 | 释放差额 |
| 实际 = 预留 | 直接完成 |
| 实际 > 预留（理论不应发生） | `outcome='OVERAGE'`、**按实际入账**、阻止该 token 后续请求、告警；**不得静默截断收费** |
| 取消 / 首字节前失败 | 释放全部预留（T5→T6） |
| midstream partial | 按已知 usage / 按次价格结算 |

**OVERAGE 定性（P1 修订）**：它是 **`outcome` 取值 + 告警**，**不是状态**——settlement 仍到达 `FINALIZED`
（钱已结清），异常只记录在 outcome 并触发告警与 token 阻断。状态机中不存在 OVERAGE 节点。

**终态不变量**：任一 settlement 到达 FINALIZED 或 RELEASED 后，其对 `quota_reserved` 与 `reserved_balance`
的贡献必须为 **0**。所有释放/结算路径测试都断言 reserved 归零。
（MANUAL_REVIEW 是**例外**：预留被有意保留，等待人工——测试须显式断言"仍持有预留"。）

## 9. SettlementWorker（P0 修订：两阶段，不反转锁序）

gateway 只提供 **`SettlementWorker.tick(): TickResult`**（一次扫描+推进），**不耦合 infra 模块**；
由 application/infra 调度器装配周期调用。

**两阶段，避免 settlement → token → account 的锁序反转（会自造死锁）**：

```
阶段 A（短事务，只碰 settlement）
  SELECT id FROM gateway_settlements
   WHERE (status IN ('FINALIZE_PENDING','RELEASE_PENDING') AND next_retry_at <= :now)
      OR (status='RESERVED'         AND created_at + :reserveTtl  <= :now)
      OR (status='UPSTREAM_STARTED' AND created_at + :upstreamTtl <= :now)
     AND lease_until <= :now
   ORDER BY next_retry_at
   LIMIT :batch
   FOR UPDATE SKIP LOCKED;                       -- PG 与 MySQL 8 均支持
  UPDATE ... SET lease_owner=:me, lease_until=:now+:leaseMs, attempts=attempts+1 WHERE id IN (...);
  COMMIT;                                        -- ← 释放 settlement 行锁

阶段 B（每条一个新事务，严格 token → account → settlement）
  SELECT ... FROM gateway_tokens          WHERE id=:tid  FOR UPDATE;
  SELECT ... FROM gateway_quota_accounts  WHERE user_id=:uid FOR UPDATE;
  SELECT ... FROM gateway_settlements     WHERE id=:sid FOR UPDATE;
  校验 lease_owner=:me AND lease_until>:now AND status=<预期旧态>；否则放弃本条（他人接管）
  执行 T4 / T6 / T5 / T9；COMMIT
```

- 重试分类（**有界退避 + jitter**）：PostgreSQL `40001`（serialization_failure）、`40P01`（deadlock_detected）；
  MySQL `1213`（deadlock）、`1205`（lock wait timeout）。
- 超 `MAX_ATTEMPTS` → T8 / T10 MANUAL_REVIEW（记 `retry_action`），不再自动重试。

## 10. 测试矩阵（真实 S8）

**PostgreSQL 跑整机 E2E**（migration runner / CAS / 并发 finalize / SKIP LOCKED+lease / 40001·40P01 / 四写点回滚 / reserved 归零）。

| # | 场景 | 断言 |
|---|---|---|
| A1 | 同 settlementId 串行 finalize ×2 | 只扣一次；第二次 AlreadyFinalized；四项一致 |
| A2 | 同 settlementId **并发** finalize ×2 | 同上；无重复台账 |
| A3 | finalize 第 1/2/3/4 写点分别注入失败 | 整事务回滚；留 FINALIZE_PENDING；worker 重放后四项一致 |
| A4 | finalize 成功但响应丢失后重试 | AlreadyFinalized；金额不变 |
| A5 | worker 与在线 finalize 竞争 | 恰好一方成功；无双扣；lease 生效 |
| A6 | reserve 后未调上游（reserveTtl） | 自动 RELEASED；reserved 归零；balance 不变 |
| A7 | UPSTREAM_STARTED 后崩溃（upstreamTtl） | **不自动退款**；T9 → MANUAL_REVIEW；**仍持有预留**；`retry_action` 已记录 |
| A8 | 同 client key + 不同 body | `409`；不执行上游；无新 settlement |
| A9 | PG 死锁/序列化失败注入 | 按 40001/40P01 重试并最终成功 |
| A11 | 全部终态 | reserved 归零；Σcharged==Σ台账==balanceΔ==quotaUsed |
| A12 | 无价/无上限配置 | 拒绝请求；不调上游；无 settlement 残留 |
| A13 | reserve 余额不足 / 预算不足 | 分别返回**不同**错误码；无残留预留；无孤儿 settlement |
| A14 | RELEASE_PENDING 失败重试 | T11 退避；超上限 → T10 MANUAL_REVIEW |
| A15 | `Idempotency-Key` 0 / 256 bytes | 均 `400`；不建 settlement |
| A16 | fingerprint 歧义反例（字段边界移动） | 不同请求 → 不同 fingerprint（LP 编码） |

## 11. 实施顺序

1. ✅ **前置**：neton-security `SecureRandom`（已落地）。
2. V004 迁移 **PG + MySQL 同批**（`reserved_balance` / `quota_reserved` / `gateway_settlements`）。
3. 状态机 + CAS 转换层（纯逻辑 + **双库**测试）。
4. reserve/finalize 接入 RelayEngine（替换现 bill 路径）。
5. `SettlementWorker.tick()` + 调度装配。
6. 真实 S8（A1–A16）；SPEC 状态更新。

**任何一项没有「实现 + PG 测试 + MySQL 测试 + 失败路径测试 + SPEC 更新」都不得标记完成。**

## 12. 数据库范围

**PostgreSQL-only**。交付形态是 Docker（compose 内置 PostgreSQL），用户不选数据库，因此不承担
多方言成本。`sql/mysql/` 冻结在 V003，不再新增；文档与控制台不出现 MySQL 支持字样。
