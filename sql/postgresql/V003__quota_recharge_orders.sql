-- V003 网关额度充值意图单（PostgreSQL 方言）
--
-- 为什么不复用 payment 的 pay_orders：pay_orders **没有 user_id 列**（订单归属由发起模块自己记），
-- 而且它是法币账本 —— 网关额度是 μUSD 账本。两者通过 merchant_order_id 关联，不共用余额。
-- 形态照 payment 的 pay_wallet_recharges：发起模块自己一行意图单，
-- merchant_order_id = 'gateway:quota:{id}'，支付成功事件按这个前缀认单。
--
-- 幂等有两层，互相独立：
--   ① 本表 pay_status 的乐观锁占位（并发/重投只有一个能改成已入账）；
--   ② gateway_quota_tx.ref 上的 uk_gateway_quota_tx_ref 部分唯一索引（ref = merchant_order_id），
--      即使 ① 被绕过，第二次入账也会撞唯一约束而不是重复加钱。
CREATE TABLE gateway_quota_recharges (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    -- 要入账的网关额度（μUSD）。由服务端按汇率算出，不接受客户端直接指定 ——
    -- 客户端能同时填 price 与 amount 就等于自己给自己定价。
    amount_micro BIGINT NOT NULL,
    -- 要收的法币金额，单位与 payment 的 pay_orders.price 一致。
    price BIGINT NOT NULL,
    -- 0=待支付 1=已入账 2=已关闭。默认待支付：充值单默认成「已入账」是危险的。
    pay_status INT NOT NULL DEFAULT 0,
    pay_order_id BIGINT,
    pay_channel_code VARCHAR(64),
    paid_at BIGINT,
    created_at BIGINT,
    updated_at BIGINT
);

-- 用户查自己的充值记录：按用户 + 倒序取最近若干条。
CREATE INDEX idx_gateway_quota_recharges_user ON gateway_quota_recharges(user_id, id DESC);
