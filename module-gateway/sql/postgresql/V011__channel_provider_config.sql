-- V011：托管上游的厂商参数（Vertex 的 project/location、Bedrock 可选的 region）
-- 放 JSON 而不是逐个加列：这些参数只对特定类型有意义，每接一家就加两列会把渠道表变成大杂烩。
ALTER TABLE gateway_channels ADD COLUMN IF NOT EXISTS provider_config VARCHAR(1024) NULL;
