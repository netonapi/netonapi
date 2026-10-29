-- V004 兑换码（MySQL 方言）
-- 设计与动因见 postgresql/V004__redemption_codes.sql，此处只记方言差异。
--
-- 差异要点：
--  - 唯一索引写在表内且**不带任何条件**：MySQL 没有部分索引，而本表本来就不做软删，
--    所以「全表唯一」在两种方言下语义一致，不存在 MySQL 侧放宽的问题。
--  - 二级索引不写 DESC：MySQL 8.0 之前会忽略它，写了反而让人以为生效了。
--    管理端按 id 倒序取批次内记录，用 (batch_id, id) 正向索引反向扫描同样能走。
CREATE TABLE gateway_redemption_codes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    quota_micro BIGINT NOT NULL,
    status INT NOT NULL DEFAULT 0,
    used_user_id BIGINT NULL,
    used_at BIGINT NULL,
    expires_at BIGINT NULL,
    batch_id VARCHAR(64) NULL,
    note VARCHAR(255) NULL,
    created_by BIGINT NULL,
    created_at BIGINT NULL,
    updated_at BIGINT NULL,
    UNIQUE KEY uk_gateway_redemption_code (code),
    KEY idx_gateway_redemption_batch (batch_id, id),
    KEY idx_gateway_redemption_status (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
