-- V016：渠道能力验证记录
--
-- 有些兼容做法只有在真实上游上验证过才可以启用。第一个用例是 Gemini thought signature 的
-- 占位值（neton-docs spec/ai.md §4.5.2）：Google 官方允许但强烈不鼓励，只在当前渠道与模型
-- 确实接受时才写入。验证由定时任务 gateway-capability-verify 对真实上游发探测请求完成，
-- 中转时只认「通过、且未过期」的记录 —— 没有记录就不启用，而不是凭一句「发布前测过」放行。
CREATE TABLE IF NOT EXISTS gateway_channel_verifications (
    id BIGSERIAL PRIMARY KEY,
    channel_id BIGINT NOT NULL,
    -- 能力名，如 gemini_signature_bypass
    capability VARCHAR(64) NOT NULL,
    -- 上游模型名（映射后的名字，即真正发给上游的那个）
    model VARCHAR(255) NOT NULL,
    -- 1=通过 0=未通过
    ok INT NOT NULL,
    verified_at BIGINT NOT NULL,
    -- 未通过时上游的原话（截断），便于运营排查
    detail VARCHAR(512),
    created_at BIGINT,
    updated_at BIGINT
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_gateway_channel_verifications
    ON gateway_channel_verifications (channel_id, capability, model);
