-- gateway 模块（postgresql）—— 1.0.0 beta1 合并基线。
--
-- 🔴 **只给全新数据库用。** 由原来的 7 个迁移脚本按执行顺序拼接而成：
-- 顺序不变、语句不变，所以结果与逐条执行完全一致。
--
-- 为什么是拼接而不是导出结构快照：这些脚本里有 init_data / seed_menus 这类
-- **种子数据**，`pg_dump --schema-only` 会把它们丢掉，而只导结构就得再手工把
-- INSERT 补回来——那一步没有任何东西能验证对错。拼接则由构造保证等价。
--
-- 拼接的代价是留下了少量互相抵消的步骤（先加列、后改列）。它们无害，但**不要**
-- 试图"顺手清理"：清理一次就等于重新引入一个没人验证过的结构。
--
-- 存量库怎么办：本发布不提供原地升级。Neton 的迁移器按 checksum 校验，V001 变了
-- 就会拒绝启动——这是有意的，见 MigrationEngine 的 CHECKSUM_MISMATCH。
--
-- 加新东西请新增 V002、V003…，不要改这个文件。


-- ─────────────────────────────────────────────────────────────
-- 原 V001__create_tables.sql
-- ─────────────────────────────────────────────────────────────

-- gateway module V001: 渠道与多 Key
CREATE TABLE gateway_channels (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    type VARCHAR(32) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    groups VARCHAR(256) NOT NULL DEFAULT 'default',
    models TEXT NOT NULL DEFAULT '',
    model_mapping TEXT,
    param_override TEXT,
    priority INT NOT NULL DEFAULT 0,
    weight INT NOT NULL DEFAULT 1,
    status INT NOT NULL DEFAULT 1,
    ttfb_timeout_ms BIGINT NOT NULL DEFAULT 30000,
    idle_timeout_ms BIGINT NOT NULL DEFAULT 90000,
    proxy_url VARCHAR(512),
    cost_discount VARCHAR(32) NOT NULL DEFAULT '1.0',
    remark VARCHAR(512),
    deleted INT NOT NULL DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT
);
CREATE INDEX idx_gateway_channels_status ON gateway_channels(status, deleted);

CREATE TABLE gateway_channel_keys (
    id BIGSERIAL PRIMARY KEY,
    channel_id BIGINT NOT NULL,
    api_key VARCHAR(1024) NOT NULL,
    status INT NOT NULL DEFAULT 1,
    fail_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(512),
    last_used_at BIGINT,
    deleted INT NOT NULL DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT
);
CREATE INDEX idx_gateway_channel_keys_channel ON gateway_channel_keys(channel_id, status, deleted);

CREATE TABLE gateway_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    key_display VARCHAR(32) NOT NULL,
    quota_budget BIGINT,
    quota_used BIGINT NOT NULL DEFAULT 0,
    expires_at BIGINT,
    allowed_models TEXT NOT NULL DEFAULT '',
    allowed_ips TEXT NOT NULL DEFAULT '',
    group_override VARCHAR(64),
    status INT NOT NULL DEFAULT 1,
    deleted INT NOT NULL DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT
);
CREATE UNIQUE INDEX uk_gateway_tokens_hash ON gateway_tokens(key_hash);
CREATE INDEX idx_gateway_tokens_user ON gateway_tokens(user_id, deleted);

CREATE TABLE gateway_quota_accounts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    balance BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT
);
CREATE UNIQUE INDEX uk_gateway_quota_accounts_user ON gateway_quota_accounts(user_id);

CREATE TABLE gateway_quota_transactions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    type VARCHAR(16) NOT NULL,
    amount BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    ref VARCHAR(128),
    created_at BIGINT
);
CREATE INDEX idx_gateway_quota_tx_user ON gateway_quota_transactions(user_id, created_at);

CREATE TABLE gateway_model_prices (
    id BIGSERIAL PRIMARY KEY,
    model VARCHAR(128) NOT NULL,
    input_price VARCHAR(32) NOT NULL DEFAULT '0',
    output_price VARCHAR(32) NOT NULL DEFAULT '0',
    cache_read_price VARCHAR(32) NOT NULL DEFAULT '0',
    cache_write_price VARCHAR(32) NOT NULL DEFAULT '0',
    per_request_price BIGINT,
    sale_override TEXT,
    source VARCHAR(16) NOT NULL DEFAULT 'manual',
    deleted INT NOT NULL DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT
);
CREATE UNIQUE INDEX uk_gateway_model_prices_model ON gateway_model_prices(model) WHERE deleted = 0;

CREATE TABLE gateway_groups (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    ratio VARCHAR(32) NOT NULL DEFAULT '1.0',
    description VARCHAR(256),
    deleted INT NOT NULL DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT
);

CREATE TABLE gateway_usage_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_id BIGINT NOT NULL,
    channel_id BIGINT NOT NULL,
    request_model VARCHAR(128) NOT NULL,
    upstream_model VARCHAR(128) NOT NULL,
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    cache_read_tokens INT NOT NULL DEFAULT 0,
    cache_write_tokens INT NOT NULL DEFAULT 0,
    charged BIGINT NOT NULL DEFAULT 0,
    cost BIGINT NOT NULL DEFAULT 0,
    ttfb_ms BIGINT,
    latency_ms BIGINT,
    stream INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ok',
    error_code VARCHAR(64),
    created_at BIGINT
);
CREATE INDEX idx_gateway_usage_logs_user ON gateway_usage_logs(user_id, created_at);
CREATE INDEX idx_gateway_usage_logs_channel ON gateway_usage_logs(channel_id, created_at);

-- 种子：默认分组
INSERT INTO gateway_groups (name, ratio, description, created_at) VALUES ('default', '1.0', '默认分组', 0);-- ── 后台菜单 ──────────────────────────────────────────────────────────
--
-- 🔴 **不写死菜单 id**：id 由 system_menus 的序列在安装时分配。
--
-- 以前每个模块把 id 硬编码在 SQL 里，模块之间就得就编号达成一致，而唯一的
-- 保护是 `ON CONFLICT (id) DO NOTHING`——撞号不会报错，只会**静默**丢菜单。
-- 实测后果：gateway 和 privchat 撞了 700-704，于是「令牌管理」「定价修改」这些
-- AI 网关的按钮被挂到了「用户管理」「群组管理」底下，而没有任何地方报错。
--
-- 现在父子关系在语句内部用**模块内唯一的菜单名**连接（同一模块内不允许重名），
-- 跨模块不再共享任何编号，撞号从结构上不可能发生。
--
-- 加菜单：往对应层级的 VALUES 里加一行即可，不需要挑号。
-- 改菜单：后续迁移按 permission 定位；若该 permission 在本模块内不唯一，
--         用 name 加父节点定位。

WITH lvl1 AS (
    INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
    VALUES
        ('AI 网关', '', 1, 0, '/gateway', NULL, 'ant-design:api-outlined', 7, 1, (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint)
    RETURNING id, name
),
lvl2 AS (
    INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
    SELECT v.name, v.permission, v.type,
           (SELECT p.id FROM lvl1 p WHERE p.name = v.parent_name),
           v.path, v.component, v.icon, v.sort, v.status, (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint
    FROM (VALUES
        ('上游渠道', 'gateway:channel:list', 2, 'AI 网关', 'channel', 'gateway/channel/index', 'ant-design:cluster-outlined', 1, 1),
        ('API 令牌', 'gateway:token:list', 2, 'AI 网关', 'token', 'gateway/token/index', 'ant-design:key-outlined', 2, 1),
        ('模型定价', 'gateway:price:list', 2, 'AI 网关', 'price', 'gateway/price/index', 'ant-design:dollar-outlined', 3, 1),
        ('用量日志', 'gateway:log:list', 2, 'AI 网关', 'log', 'gateway/log/index', 'ant-design:file-text-outlined', 4, 1),
        ('结算待处理', 'gateway:settlement:list', 2, 'AI 网关', 'settlement', 'gateway/settlement/index', 'ant-design:audit-outlined', 5, 1)
    ) AS v(name, permission, type, parent_name, path, component, icon, sort, status)
    RETURNING id, name
),
lvl3 AS (
    INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
    SELECT v.name, v.permission, v.type,
           (SELECT p.id FROM lvl2 p WHERE p.name = v.parent_name),
           v.path, v.component, v.icon, v.sort, v.status, (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint
    FROM (VALUES
        ('渠道创建', 'gateway:channel:create', 3, '上游渠道', '', NULL, '', 1, 1),
        ('渠道修改', 'gateway:channel:update', 3, '上游渠道', '', NULL, '', 2, 1),
        ('渠道删除', 'gateway:channel:delete', 3, '上游渠道', '', NULL, '', 3, 1),
        ('令牌创建', 'gateway:token:create', 3, 'API 令牌', '', NULL, '', 1, 1),
        ('令牌管理', 'gateway:token:update', 3, 'API 令牌', '', NULL, '', 2, 1),
        ('令牌删除', 'gateway:token:delete', 3, 'API 令牌', '', NULL, '', 3, 1),
        ('定价创建', 'gateway:price:create', 3, '模型定价', '', NULL, '', 1, 1),
        ('定价修改', 'gateway:price:update', 3, '模型定价', '', NULL, '', 2, 1),
        ('定价删除', 'gateway:price:delete', 3, '模型定价', '', NULL, '', 3, 1),
        ('配额管理', 'gateway:quota:grant', 3, '用量日志', '', NULL, '', 1, 1),
        ('执行结算恢复', 'gateway:settlement:tick', 3, '结算待处理', '', NULL, '', 1, 1)
    ) AS v(name, permission, type, parent_name, path, component, icon, sort, status)
    RETURNING id, name
)
SELECT count(*) FROM (SELECT id, name FROM lvl1
        UNION ALL SELECT id, name FROM lvl2
        UNION ALL SELECT id, name FROM lvl3) t;




-- ─────────────────────────────────────────────────────────────
-- 原 V003__reliability.sql
-- ─────────────────────────────────────────────────────────────

-- gateway V003: 账务可靠性收口——结算幂等、渠道 429 冷却

-- 结算幂等：ref 唯一（仅非空），同一 usage/订单的台账不可重复入账（防重试/重放重复扣费）
-- 升级已有库：建唯一索引前先校验存量无重复 ref，有重复则失败并要求人工对账（不静默）
DO $$
DECLARE dup INT;
BEGIN
    SELECT COUNT(*) INTO dup FROM (
        SELECT ref FROM gateway_quota_transactions WHERE ref IS NOT NULL GROUP BY ref HAVING COUNT(*) > 1
    ) d;
    IF dup > 0 THEN
        RAISE EXCEPTION 'gateway_quota_transactions has % duplicate non-null ref value(s); reconcile before applying uk_gateway_quota_tx_ref', dup;
    END IF;
END $$;

CREATE UNIQUE INDEX uk_gateway_quota_tx_ref ON gateway_quota_transactions(ref) WHERE ref IS NOT NULL;

-- 渠道 429 冷却：cooldown_until 之前该渠道不参与候选（backoff，避免立即重试被限流的上游）
ALTER TABLE gateway_channels ADD COLUMN cooldown_until BIGINT NOT NULL DEFAULT 0;


-- ─────────────────────────────────────────────────────────────
-- 原 V004__durable_settlement.sql
-- ─────────────────────────────────────────────────────────────

-- gateway V004: durable settlement（reserve/finalize + 幂等 + 崩溃恢复）
-- 设计见 docs/V004-durable-settlement-design.md。settlement 表同时是账务记录与 durable queue。

-- 预留双记：可用余额 = balance - reserved_balance；可用预算 = quota_budget - quota_used - quota_reserved
ALTER TABLE gateway_quota_accounts ADD COLUMN reserved_balance BIGINT NOT NULL DEFAULT 0;
ALTER TABLE gateway_tokens         ADD COLUMN quota_reserved   BIGINT NOT NULL DEFAULT 0;

CREATE TABLE gateway_settlements (
    id                  BIGSERIAL PRIMARY KEY,
    -- CSPRNG 128-bit hex：一次真实上游执行的账务主键
    settlement_id       VARCHAR(32)  NOT NULL,
    -- SHA-256(客户端 Idempotency-Key)，仅作外部映射键；定长，不存原文
    client_idem_key     VARCHAR(64),
    -- SHA-256(长度前缀编码的请求摘要)，用于同 key 的一致性校验
    request_fingerprint VARCHAR(64),
    user_id             BIGINT       NOT NULL,
    token_id            BIGINT       NOT NULL,
    channel_id          BIGINT,
    request_model       VARCHAR(128) NOT NULL,
    endpoint            VARCHAR(128) NOT NULL,
    -- RESERVED / UPSTREAM_STARTED / FINALIZE_PENDING / FINALIZED / RELEASE_PENDING / RELEASED / MANUAL_REVIEW
    status              VARCHAR(24)  NOT NULL,
    -- FINALIZE / RELEASE：进 MANUAL_REVIEW 时记录当时在做什么
    retry_action        VARCHAR(16),
    reserved_amount     BIGINT       NOT NULL DEFAULT 0,
    charged             BIGINT       NOT NULL DEFAULT 0,
    cost                BIGINT       NOT NULL DEFAULT 0,
    -- reserve 时固化，finalize 必须按快照计价（中途改价不影响在途请求）
    pricing_snapshot    TEXT         NOT NULL DEFAULT '',
    usage_json          TEXT,
    estimated           INT          NOT NULL DEFAULT 0,
    terminal_seen       INT          NOT NULL DEFAULT 0,
    -- ok / partial / error / OVERAGE（OVERAGE 是 outcome 而非状态）
    outcome             VARCHAR(16),
    attempts            INT          NOT NULL DEFAULT 0,
    next_retry_at       BIGINT       NOT NULL DEFAULT 0,
    lease_owner         VARCHAR(32),
    lease_until         BIGINT       NOT NULL DEFAULT 0,
    last_error          VARCHAR(500),
    created_at          BIGINT,
    updated_at          BIGINT
);

CREATE UNIQUE INDEX uk_gateway_settlements_sid ON gateway_settlements(settlement_id);
-- 外部幂等映射：同一 (用户,令牌,端点) 下客户端 key 唯一；部分索引使无 key 的请求不受约束
CREATE UNIQUE INDEX uk_gateway_settlements_idem
    ON gateway_settlements(user_id, token_id, endpoint, client_idem_key)
    WHERE client_idem_key IS NOT NULL;
-- worker 取件：待重试扫描
CREATE INDEX idx_gateway_settlements_retry ON gateway_settlements(status, next_retry_at, lease_until);
-- worker 取件：RESERVED / UPSTREAM_STARTED 的 TTL 扫描
CREATE INDEX idx_gateway_settlements_ttl ON gateway_settlements(status, created_at);
-- 运营查询：按用户看账
CREATE INDEX idx_gateway_settlements_user ON gateway_settlements(user_id, created_at);

-- reserve 的输出上限来源：请求未传 max_tokens 时按模型目录的默认上限预留（缺失则拒绝请求，不免费放行）
ALTER TABLE gateway_model_prices ADD COLUMN default_max_output_tokens INT;

-- usage_log 维度随 settlement 持久化：finalize（含 worker 重放）需在同一事务内写出完整日志
ALTER TABLE gateway_settlements ADD COLUMN upstream_model VARCHAR(128) NOT NULL DEFAULT '';
ALTER TABLE gateway_settlements ADD COLUMN ttfb_ms   BIGINT;
ALTER TABLE gateway_settlements ADD COLUMN latency_ms BIGINT;
ALTER TABLE gateway_settlements ADD COLUMN stream    INT NOT NULL DEFAULT 0;
ALTER TABLE gateway_settlements ADD COLUMN prompt_tokens     INT NOT NULL DEFAULT 0;
ALTER TABLE gateway_settlements ADD COLUMN completion_tokens INT NOT NULL DEFAULT 0;
ALTER TABLE gateway_settlements ADD COLUMN cache_read_tokens  INT NOT NULL DEFAULT 0;
ALTER TABLE gateway_settlements ADD COLUMN cache_write_tokens INT NOT NULL DEFAULT 0;


-- ─────────────────────────────────────────────────────────────
-- 原 V005__rate_limits.sql
-- ─────────────────────────────────────────────────────────────

-- gateway V005: 令牌级速率限制（RPM / TPM / 并发流）
-- 无限流时单个用户即可打爆上游配额与成本，属上线前必备的成本敞口控制。
-- NULL = 不限；正整数 = 上限。

ALTER TABLE gateway_tokens ADD COLUMN rpm_limit         INT;
ALTER TABLE gateway_tokens ADD COLUMN tpm_limit         INT;
ALTER TABLE gateway_tokens ADD COLUMN concurrency_limit INT;


-- ─────────────────────────────────────────────────────────────
-- 原 V007__channel_capabilities.sql
-- ─────────────────────────────────────────────────────────────

-- gateway V007: 渠道端点能力（capability routing）
-- 此前 /v1/embeddings 只要模型名命中就会被路由：打到只会 chat 的上游必然 404，而钱已预留、
-- 日志已脏、重试还会把同一堵墙撞三遍。能力必须是渠道的显式属性，不靠上游类型猜。
--
-- 默认 'chat'：升级后存量渠道只服务对话端点。确实提供向量的渠道需补 capabilities='chat,embeddings'
-- （管理台渠道表单可改）。宁可升级后 embeddings 明确报「无可用渠道」，也不要悄悄把向量请求
-- 送进 chat 上游、再用上游的 404 来发现配置错了。

ALTER TABLE gateway_channels ADD COLUMN capabilities VARCHAR(128) NOT NULL DEFAULT 'chat';
