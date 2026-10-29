-- V020：渠道真实成本（netonapi-backend/docs/channel-cost-and-price-sync-design.md §4）。
-- cost_source：official = 官方价 × cost_discount（默认）；upstream = 上游价目接口；manual = 按模型手填。
ALTER TABLE gateway_channels ADD COLUMN IF NOT EXISTS cost_source VARCHAR(16) NOT NULL DEFAULT 'official';
ALTER TABLE gateway_channels ADD COLUMN IF NOT EXISTS upstream_pricing_format VARCHAR(16);
ALTER TABLE gateway_channels ADD COLUMN IF NOT EXISTS upstream_pricing_url VARCHAR(512);
ALTER TABLE gateway_channels ADD COLUMN IF NOT EXISTS upstream_group_ratio VARCHAR(32) NOT NULL DEFAULT '1';
ALTER TABLE gateway_channels ADD COLUMN IF NOT EXISTS upstream_credit_rate VARCHAR(32) NOT NULL DEFAULT '1';
ALTER TABLE gateway_channels ADD COLUMN IF NOT EXISTS cost_synced_at BIGINT;
ALTER TABLE gateway_channels ADD COLUMN IF NOT EXISTS cost_sync_error TEXT;

-- 渠道 × 请求模型（对外模型）的实际成本价；有这一行时结算按它记成本，不再乘渠道折扣。
CREATE TABLE IF NOT EXISTS gateway_channel_model_costs (
    id                BIGSERIAL    PRIMARY KEY,
    channel_id        BIGINT       NOT NULL,
    model             VARCHAR(128) NOT NULL,
    input_price       VARCHAR(32)  NOT NULL,
    output_price      VARCHAR(32)  NOT NULL,
    cache_read_price  VARCHAR(32)  NOT NULL DEFAULT '0',
    cache_write_price VARCHAR(32)  NOT NULL DEFAULT '0',
    per_request_price BIGINT,
    tiers             TEXT,
    source            VARCHAR(16)  NOT NULL,
    stale             SMALLINT     NOT NULL DEFAULT 0,
    updated_at        BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (channel_id, model)
);

-- 调价记录也记渠道成本的变动；status = pending 的是待确认的异常变动（大幅涨跌或变 0）。
ALTER TABLE gateway_price_history ADD COLUMN IF NOT EXISTS channel_id BIGINT;
ALTER TABLE gateway_price_history ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'applied';
CREATE INDEX IF NOT EXISTS idx_gateway_price_history_pending ON gateway_price_history (channel_id, status);
