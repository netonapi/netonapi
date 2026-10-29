-- V003 网关额度充值意图单（MySQL 方言）
-- 设计与动因见 postgresql/V003__quota_recharge_orders.sql，此处只记方言差异。
CREATE TABLE gateway_quota_recharges (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    amount_micro BIGINT NOT NULL,
    price BIGINT NOT NULL,
    pay_status INT NOT NULL DEFAULT 0,
    pay_order_id BIGINT NULL,
    pay_channel_code VARCHAR(64) NULL,
    paid_at BIGINT NULL,
    created_at BIGINT NULL,
    updated_at BIGINT NULL,
    KEY idx_gateway_quota_recharges_user (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
