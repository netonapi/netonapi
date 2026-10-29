-- 管理台表单里代理留空提交的是 ""，曾原样落库；建 HTTP 客户端时被当成非法代理地址拒绝，
-- 这些渠道拉模型与转发全部失败。空白统一改回 NULL（= 不走代理）。代码侧已同时把空白当作没有代理。
UPDATE gateway_channels SET proxy_url = NULL WHERE proxy_url IS NOT NULL AND btrim(proxy_url) = '';
