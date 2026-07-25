-- gateway V004: durable settlement（reserve/finalize + 幂等 + 崩溃恢复）
-- 设计见 docs/V004-durable-settlement-design.md。settlement 表同时是账务记录与 durable queue。

-- 预留双记：可用余额 = balance - reserved_balance；可用预算 = quota_budget - quota_used - quota_reserved
ALTER TABLE gateway_quota_accounts ADD COLUMN reserved_balance BIGINT NOT NULL DEFAULT 0;
ALTER TABLE gateway_tokens         ADD COLUMN quota_reserved   BIGINT NOT NULL DEFAULT 0;

CREATE TABLE gateway_settlements (
    id                  BIGSERIAL PRIMARY KEY,
    -- CSPRNG 128-bit hex：一次真实上游执行的账务主键
    settlement_id       CHAR(32)     NOT NULL,
    -- SHA-256(客户端 Idempotency-Key)，仅作外部映射键；定长，不存原文
    client_idem_key     CHAR(64),
    -- SHA-256(长度前缀编码的请求摘要)，用于同 key 的一致性校验
    request_fingerprint CHAR(64),
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
    lease_owner         CHAR(32),
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
