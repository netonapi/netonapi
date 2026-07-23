-- gateway V003: 账务可靠性收口——结算幂等、渠道 429 冷却

-- 结算幂等：ref 唯一（仅非空），同一 usage/订单的台账不可重复入账（防重试/重放重复扣费）
CREATE UNIQUE INDEX uk_gateway_quota_tx_ref ON gateway_quota_transactions(ref) WHERE ref IS NOT NULL;

-- 渠道 429 冷却：cooldown_until 之前该渠道不参与候选（backoff，避免立即重试被限流的上游）
ALTER TABLE gateway_channels ADD COLUMN cooldown_until BIGINT NOT NULL DEFAULT 0;
