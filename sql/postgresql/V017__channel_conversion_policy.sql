-- V017：跨协议转换的损耗策略与模型名推理尾缀开关（docs/neton-ai-protocol-adoption.md §4.2 / §4.3）
-- loss_policy：allow（默认，完成转换并记录诊断）/ safe（工具行为、内容或必要状态会变时拒绝，换下一个渠道）/ strict（任何诊断都拒绝）
-- reasoning_suffix：1 = 解析请求模型名的 -thinking / -high 等尾缀（去掉尾缀再做模型映射，并把推理意图写进请求）
ALTER TABLE gateway_channels ADD COLUMN IF NOT EXISTS loss_policy VARCHAR(16) NOT NULL DEFAULT 'allow';
ALTER TABLE gateway_channels ADD COLUMN IF NOT EXISTS reasoning_suffix SMALLINT NOT NULL DEFAULT 0;
