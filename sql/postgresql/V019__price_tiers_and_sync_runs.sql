-- V019：按上下文长度分档的价格，以及定时价源检查的结果。
-- tiers：JSON 数组 [{maxPromptTokens, input, output, cacheRead, cacheWrite}]，按总输入 token 数选档，最后一档 maxPromptTokens 为 null（不封顶）；空 = 单档（用四个价格列）。
ALTER TABLE gateway_model_prices ADD COLUMN IF NOT EXISTS tiers TEXT;

-- 定时任务 gateway-price-sync 每次检查价源的结果：只记录差异，不写价格表，等运营确认。
CREATE TABLE IF NOT EXISTS gateway_price_sync_runs (
    id          BIGSERIAL PRIMARY KEY,
    checked_at  BIGINT       NOT NULL,
    source      VARCHAR(512),
    changed     INT          NOT NULL DEFAULT 0,
    new_models  INT          NOT NULL DEFAULT 0,
    unparsable  INT          NOT NULL DEFAULT 0,
    error       TEXT
);
CREATE INDEX IF NOT EXISTS idx_gateway_price_sync_runs_checked ON gateway_price_sync_runs (checked_at DESC);
