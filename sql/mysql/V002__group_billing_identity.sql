-- V002 分组计费身份：code 即身份、name 仅显示（MySQL 方言）
-- 设计与动因见 postgresql/V002__group_billing_identity.sql，此处只记方言差异。

-- MySQL 没有 ALTER COLUMN ... SET NOT NULL，用 MODIFY COLUMN 收紧。
ALTER TABLE gateway_groups ADD COLUMN code VARCHAR(64) NULL;
UPDATE gateway_groups SET code = name WHERE code IS NULL;
ALTER TABLE gateway_groups MODIFY COLUMN code VARCHAR(64) NOT NULL;

ALTER TABLE gateway_groups ADD COLUMN member_group_id BIGINT NULL;

-- ⚠️ 方言差异（既有坑，非本次引入）：MySQL 不支持部分索引，本仓的
-- uk_gateway_model_prices_model 在 MySQL 侧同样是无条件唯一索引。跟随该 pattern
-- 的后果是：软删一个组之后，它的 code 在 MySQL 下永久占位、无法复用同名重建
-- （PostgreSQL / SQLite 用 WHERE deleted = 0 则不受影响）。
-- 这里选择与既有 pattern 一致而不是自创新写法，避免三方言以**新的**方式分叉；
-- 缓解措施是 GroupController 侧对被引用的组禁止删除，让软删尽量不发生。
-- member_group_id 上的唯一索引对 NULL 不生效（MySQL 允许多行 NULL），未映射的组不受约束。
CREATE UNIQUE INDEX uk_gateway_groups_code ON gateway_groups(code);
CREATE UNIQUE INDEX uk_gateway_groups_member_group ON gateway_groups(member_group_id);

CREATE TABLE gateway_group_overrides (
    user_id BIGINT NOT NULL PRIMARY KEY,
    group_code VARCHAR(64) NOT NULL,
    remark VARCHAR(256),
    created_at BIGINT,
    updated_at BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
