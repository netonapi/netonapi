-- gateway V003: 账务可靠性收口——结算幂等、渠道 429 冷却（MySQL；唯一索引允许多个 NULL，无需部分索引）

-- 升级已有库：建唯一索引前先校验存量无重复 ref，有重复则中止迁移并要求人工对账（与 PG 侧 DO 块对等）。
-- MySQL 无 DO 块：用「标量子查询返回多行」强制报错（ERROR 1242）中止；无重复时 IF 短路返回 'ok'。
SELECT IF(
    (SELECT COUNT(*) FROM (
        SELECT ref FROM gateway_quota_transactions WHERE ref IS NOT NULL GROUP BY ref HAVING COUNT(*) > 1
    ) d) > 0,
    (SELECT 'DUPLICATE ref in gateway_quota_transactions: reconcile before uk_gateway_quota_tx_ref'
     UNION ALL SELECT 'abort'),
    'ok'
) AS duplicate_ref_precheck;

CREATE UNIQUE INDEX uk_gateway_quota_tx_ref ON gateway_quota_transactions(ref);

ALTER TABLE gateway_channels ADD COLUMN cooldown_until BIGINT NOT NULL DEFAULT 0;
