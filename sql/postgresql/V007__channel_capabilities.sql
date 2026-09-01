-- gateway V007: 渠道端点能力（capability routing）
-- 此前 /v1/embeddings 只要模型名命中就会被路由：打到只会 chat 的上游必然 404，而钱已预留、
-- 日志已脏、重试还会把同一堵墙撞三遍。能力必须是渠道的显式属性，不靠上游类型猜。
--
-- 默认 'chat'：升级后存量渠道只服务对话端点。确实提供向量的渠道需补 capabilities='chat,embeddings'
-- （管理台渠道表单可改）。宁可升级后 embeddings 明确报「无可用渠道」，也不要悄悄把向量请求
-- 送进 chat 上游、再用上游的 404 来发现配置错了。

ALTER TABLE gateway_channels ADD COLUMN capabilities VARCHAR(128) NOT NULL DEFAULT 'chat';
