-- V004 兑换码（PostgreSQL 方言）
--
-- 兑换码是运营侧的营收工具：批量生成 → 线下销售 / 活动发放 / 客诉补偿 → 用户自助兑换成网关额度。
-- 台账 gateway_quota_transactions 从 V001 起就在注释里预留了 type='redeem' 与「ref = 兑换码」，
-- 但直到本迁移之前没有任何代码会产生这种记录 —— 预留位一直是空的。
--
-- 两个刻意的取舍，都关乎钱：
--
-- ① **不做软删**。这是钱据（与 gateway_quota_recharges 同一判断）。软删会让作废的码从
--    `WHERE deleted = 0` 的部分唯一索引里消失，于是同一个 code 可以被重新生成 ——
--    那就是一张码能兑两次。作废用 status=2 表达、行永远留着，唯一索引因此必须是**全表唯一**。
--
-- ② **code 存明文**，不存哈希。运营要能把码导出发给客户、要在后台反复查看某场活动的码，
--    只存哈希会让这两件事都做不了。代价是库泄露即额度泄露，因此用足够熵的码体兜住：
--    20 字符、31 字符集（见 RedemptionCodeLogic.ALPHABET）≈ 99 bit，暴力枚举不可行；
--    且兑换失败一律不回面额，枚举即使命中也拿不到「这张值多少钱」。
CREATE TABLE gateway_redemption_codes (
    id BIGSERIAL PRIMARY KEY,
    -- 用户兑换时输入的码体。归一化（去空白与连字符、转大写）后比对，见 RedemptionCodeLogic。
    code VARCHAR(64) NOT NULL,
    -- 面额 μUSD。生成时由管理员指定，兑换时**不接受客户端改写** ——
    -- 客户端能填面额就等于自己给自己定价（与 gateway_quota_recharges.amount_micro 同理）。
    quota_micro BIGINT NOT NULL,
    -- 0=未用 1=已用 2=作废。默认未用。
    status INT NOT NULL DEFAULT 0,
    used_user_id BIGINT,
    used_at BIGINT,
    -- 过期时刻 epoch millis；NULL = 永不过期。
    expires_at BIGINT,
    -- 批次号：一次生成 N 张归一批，便于按批统计核销率与整批作废。
    batch_id VARCHAR(64),
    -- 运营备注（活动名 / 客户名）。会出现在管理端列表里，不进用户响应。
    note VARCHAR(255),
    -- 生成者（管理员 id）。谁造的码谁负责，客诉时要能追到人。
    created_by BIGINT,
    created_at BIGINT,
    updated_at BIGINT
);

-- 全表唯一，不带 WHERE deleted = 0：见上面取舍 ①。
CREATE UNIQUE INDEX uk_gateway_redemption_code ON gateway_redemption_codes(code);
-- 管理端按批次核销率、按状态筛未用码。
CREATE INDEX idx_gateway_redemption_batch ON gateway_redemption_codes(batch_id, id DESC);
CREATE INDEX idx_gateway_redemption_status ON gateway_redemption_codes(status, id DESC);
