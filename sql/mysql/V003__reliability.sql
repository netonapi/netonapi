-- gateway V003: 账务可靠性收口——结算幂等、渠道 429 冷却（MySQL；唯一索引允许多个 NULL，无需部分索引）

CREATE UNIQUE INDEX uk_gateway_quota_tx_ref ON gateway_quota_transactions(ref);

ALTER TABLE gateway_channels ADD COLUMN cooldown_until BIGINT NOT NULL DEFAULT 0;
