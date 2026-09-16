-- V008：Azure OpenAI 渠道的 api-version
--
-- Azure 的端点是 /openai/deployments/{deployment}/chat/completions?api-version=…，
-- 没有 api-version 直接 400。此前 azure_openai 类型只是选项里有、走的却是 OpenAI 的
-- Bearer 头 + /v1/chat/completions，实际一次也没通过。版本号随 Azure 发布节奏变，
-- 放渠道上让运营自己填；留空则由代码给一个当前稳定版。
ALTER TABLE gateway_channels ADD COLUMN IF NOT EXISTS api_version VARCHAR(32) NULL;
