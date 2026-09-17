-- V010：渠道定时测速结果
--
-- 渠道健康此前只有「失败计数 → 自动挂起」这一条被动路径，要等真实流量撞上才知道坏了。
-- 定时任务 gateway-channel-probe 每 5 分钟用渠道自己的 Key 打一条 max_tokens=1 的请求，
-- 把结果记在渠道行上：管理台渠道表与概览直接展示延迟与最近一次失败原因。
ALTER TABLE gateway_channels ADD COLUMN IF NOT EXISTS last_probe_at BIGINT NULL;
ALTER TABLE gateway_channels ADD COLUMN IF NOT EXISTS last_probe_ms BIGINT NULL;
ALTER TABLE gateway_channels ADD COLUMN IF NOT EXISTS last_probe_ok INT NULL;
ALTER TABLE gateway_channels ADD COLUMN IF NOT EXISTS last_probe_error VARCHAR(512) NULL;
