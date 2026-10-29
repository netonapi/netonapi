-- 定价规则与模型开放范围（docs/gateway-pricing-spec.md §4.3）
--
-- 一条规则 = 「分组或用户 × 模型」的约定：PLATFORM（平台价 × 分组倍率，只用来开放限定模型）/
-- OFFICIAL_DISCOUNT（官方价 × discount）/ FIXED（固定售价，prices 与 sale_override 同结构）。
-- 查价 = 用户规则 ?: 分组规则 ?: 平台价 × 分组倍率；命中折扣 / 固定价时不再乘分组倍率。
CREATE TABLE IF NOT EXISTS gateway_price_rules (
    id           BIGSERIAL PRIMARY KEY,
    subject_type VARCHAR(8)   NOT NULL,
    subject_id   VARCHAR(64)  NOT NULL,
    model        VARCHAR(128) NOT NULL,
    mode         VARCHAR(24)  NOT NULL,
    discount     VARCHAR(16),
    prices       TEXT,
    remark       VARCHAR(256),
    created_at   BIGINT,
    updated_at   BIGINT,
    CONSTRAINT uk_gateway_price_rules UNIQUE (subject_type, subject_id, model)
);
CREATE INDEX IF NOT EXISTS idx_gateway_price_rules_model ON gateway_price_rules (model);

-- 上架模型的开放范围：ALL = 所有用户；RULES_ONLY = 只对有规则的用户 / 分组开放
ALTER TABLE gateway_model_prices ADD COLUMN IF NOT EXISTS access VARCHAR(16) NOT NULL DEFAULT 'ALL';
