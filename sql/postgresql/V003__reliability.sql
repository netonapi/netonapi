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
