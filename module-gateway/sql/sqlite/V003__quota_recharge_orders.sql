-- V003 网关额度充值意图单（SQLite 方言）
-- 设计与动因见 postgresql/V003__quota_recharge_orders.sql。
-- 注意：NanoGate 只嵌入 postgres|mysql 两种方言（见 module 的 build 脚本 ngMigrationDialect），
-- 这份存在只是为了三方言迁移集不分叉，本地 dev/test 用得上。
CREATE TABLE gateway_quota_recharges (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id BIGINT NOT NULL,
    amount_micro BIGINT NOT NULL,
    price BIGINT NOT NULL,
    pay_status INT NOT NULL DEFAULT 0,
    pay_order_id BIGINT,
    pay_channel_code VARCHAR(64),
    paid_at BIGINT,
    created_at BIGINT,
    updated_at BIGINT
);

CREATE INDEX idx_gateway_quota_recharges_user ON gateway_quota_recharges(user_id, id DESC);
