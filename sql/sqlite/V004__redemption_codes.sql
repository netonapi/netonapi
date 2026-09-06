-- V004 兑换码（SQLite 方言）
-- 设计与动因见 postgresql/V004__redemption_codes.sql。
-- 注意：NanoGate 只嵌入 postgres|mysql 两种方言（见 module 的 build 脚本 ngMigrationDialect），
-- 这份存在只是为了三方言迁移集不分叉，本地 dev/test 用得上。
CREATE TABLE gateway_redemption_codes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code VARCHAR(64) NOT NULL,
    quota_micro BIGINT NOT NULL,
    status INT NOT NULL DEFAULT 0,
    used_user_id BIGINT,
    used_at BIGINT,
    expires_at BIGINT,
    batch_id VARCHAR(64),
    note VARCHAR(255),
    created_by BIGINT,
    created_at BIGINT,
    updated_at BIGINT
);

CREATE UNIQUE INDEX uk_gateway_redemption_code ON gateway_redemption_codes(code);
CREATE INDEX idx_gateway_redemption_batch ON gateway_redemption_codes(batch_id, id DESC);
CREATE INDEX idx_gateway_redemption_status ON gateway_redemption_codes(status, id DESC);
