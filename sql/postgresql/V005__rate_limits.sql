-- gateway V005: 令牌级速率限制（RPM / TPM / 并发流）
-- 无限流时单个用户即可打爆上游配额与成本，属上线前必备的成本敞口控制。
-- NULL = 不限；正整数 = 上限。

ALTER TABLE gateway_tokens ADD COLUMN rpm_limit         INT;
ALTER TABLE gateway_tokens ADD COLUMN tpm_limit         INT;
ALTER TABLE gateway_tokens ADD COLUMN concurrency_limit INT;
