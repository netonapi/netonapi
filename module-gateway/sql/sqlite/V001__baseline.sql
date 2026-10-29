-- gateway 模块（sqlite）—— 1.0.0 beta1 合并基线。
--
-- 🔴 **只给全新数据库用。** 由原来的 2 个迁移脚本按执行顺序拼接而成：
-- 顺序不变、语句不变，所以结果与逐条执行完全一致。
--
-- 为什么是拼接而不是导出结构快照：这些脚本里有 init_data / seed_menus 这类
-- **种子数据**，`pg_dump --schema-only` 会把它们丢掉，而只导结构就得再手工把
-- INSERT 补回来——那一步没有任何东西能验证对错。拼接则由构造保证等价。
--
-- 拼接的代价是留下了少量互相抵消的步骤（先加列、后改列）。它们无害，但**不要**
-- 试图"顺手清理"：清理一次就等于重新引入一个没人验证过的结构。
--
-- 存量库怎么办：本发布不提供原地升级。Neton 的迁移器按 checksum 校验，V001 变了
-- 就会拒绝启动——这是有意的，见 MigrationEngine 的 CHECKSUM_MISMATCH。
--
-- 加新东西请新增 V002、V003…，不要改这个文件。


-- ─────────────────────────────────────────────────────────────
-- 原 V001__create_tables.sql
-- ─────────────────────────────────────────────────────────────

-- gateway module V001: 渠道与多 Key
CREATE TABLE gateway_channels (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    type TEXT NOT NULL,
    base_url TEXT NOT NULL,
    groups TEXT NOT NULL DEFAULT 'default',
    models TEXT NOT NULL DEFAULT '',
    model_mapping TEXT,
    param_override TEXT,
    priority INT NOT NULL DEFAULT 0,
    weight INT NOT NULL DEFAULT 1,
    status INT NOT NULL DEFAULT 1,
    ttfb_timeout_ms BIGINT NOT NULL DEFAULT 30000,
    idle_timeout_ms BIGINT NOT NULL DEFAULT 90000,
    proxy_url TEXT,
    cost_discount TEXT NOT NULL DEFAULT '1.0',
    remark TEXT,
    deleted INT NOT NULL DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT
);
CREATE INDEX idx_gateway_channels_status ON gateway_channels(status, deleted);

CREATE TABLE gateway_channel_keys (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    channel_id BIGINT NOT NULL,
    api_key TEXT NOT NULL,
    status INT NOT NULL DEFAULT 1,
    fail_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    last_used_at BIGINT,
    deleted INT NOT NULL DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT
);
CREATE INDEX idx_gateway_channel_keys_channel ON gateway_channel_keys(channel_id, status, deleted);

CREATE TABLE gateway_tokens (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id BIGINT NOT NULL,
    name TEXT NOT NULL,
    key_hash TEXT NOT NULL,
    key_display TEXT NOT NULL,
    quota_budget BIGINT,
    quota_used BIGINT NOT NULL DEFAULT 0,
    expires_at BIGINT,
    allowed_models TEXT NOT NULL DEFAULT '',
    allowed_ips TEXT NOT NULL DEFAULT '',
    group_override TEXT,
    status INT NOT NULL DEFAULT 1,
    deleted INT NOT NULL DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT
);
CREATE UNIQUE INDEX uk_gateway_tokens_hash ON gateway_tokens(key_hash);
CREATE INDEX idx_gateway_tokens_user ON gateway_tokens(user_id, deleted);

CREATE TABLE gateway_quota_accounts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id BIGINT NOT NULL,
    balance BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT
);
CREATE UNIQUE INDEX uk_gateway_quota_accounts_user ON gateway_quota_accounts(user_id);

CREATE TABLE gateway_quota_transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id BIGINT NOT NULL,
    type TEXT NOT NULL,
    amount BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    ref TEXT,
    created_at BIGINT
);
CREATE INDEX idx_gateway_quota_tx_user ON gateway_quota_transactions(user_id, created_at);

CREATE TABLE gateway_model_prices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    model TEXT NOT NULL,
    input_price TEXT NOT NULL DEFAULT '0',
    output_price TEXT NOT NULL DEFAULT '0',
    cache_read_price TEXT NOT NULL DEFAULT '0',
    cache_write_price TEXT NOT NULL DEFAULT '0',
    per_request_price BIGINT,
    sale_override TEXT,
    source TEXT NOT NULL DEFAULT 'manual',
    deleted INT NOT NULL DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT
);
CREATE UNIQUE INDEX uk_gateway_model_prices_model ON gateway_model_prices(model);

CREATE TABLE gateway_groups (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    ratio TEXT NOT NULL DEFAULT '1.0',
    description TEXT,
    deleted INT NOT NULL DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT
);

CREATE TABLE gateway_usage_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id BIGINT NOT NULL,
    token_id BIGINT NOT NULL,
    channel_id BIGINT NOT NULL,
    request_model TEXT NOT NULL,
    upstream_model TEXT NOT NULL,
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    cache_read_tokens INT NOT NULL DEFAULT 0,
    cache_write_tokens INT NOT NULL DEFAULT 0,
    charged BIGINT NOT NULL DEFAULT 0,
    cost BIGINT NOT NULL DEFAULT 0,
    ttfb_ms BIGINT,
    latency_ms BIGINT,
    stream INT NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'ok',
    error_code TEXT,
    created_at BIGINT
);
CREATE INDEX idx_gateway_usage_logs_user ON gateway_usage_logs(user_id, created_at);
CREATE INDEX idx_gateway_usage_logs_channel ON gateway_usage_logs(channel_id, created_at);

-- 种子：默认分组
INSERT INTO gateway_groups (name, ratio, description, created_at) VALUES ('default', '1.0', '默认分组', 0);


-- ─────────────────────────────────────────────────────────────
-- 原 V002__seed_menus.sql
-- ─────────────────────────────────────────────────────────────

-- gateway 后台菜单（父菜单 id=7，子项 700-703；权限码对齐 controller）
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (7, 'AI 网关', '', 1, 0, '/gateway', NULL, 'ant-design:api-outlined', 7, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (700, '上游渠道', 'gateway:channel:list', 2, 7, 'channel', 'gateway/channel/index', 'ant-design:cluster-outlined', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (701, 'API 令牌', 'gateway:token:list', 2, 7, 'token', 'gateway/token/index', 'ant-design:key-outlined', 2, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (702, '模型定价', 'gateway:price:list', 2, 7, 'price', 'gateway/price/index', 'ant-design:dollar-outlined', 3, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (703, '用量日志', 'gateway:log:list', 2, 7, 'log', 'gateway/log/index', 'ant-design:file-text-outlined', 4, 1, 0, 0) ON CONFLICT (id) DO NOTHING;

-- 按钮级权限（type=3，隶属各列表菜单）
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (7000, '渠道创建', 'gateway:channel:create', 3, 700, '', NULL, '', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (7001, '渠道修改', 'gateway:channel:update', 3, 700, '', NULL, '', 2, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (7002, '渠道删除', 'gateway:channel:delete', 3, 700, '', NULL, '', 3, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (7010, '令牌创建', 'gateway:token:create', 3, 701, '', NULL, '', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (7011, '令牌管理', 'gateway:token:update', 3, 701, '', NULL, '', 2, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (7012, '令牌删除', 'gateway:token:delete', 3, 701, '', NULL, '', 3, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (7020, '定价创建', 'gateway:price:create', 3, 702, '', NULL, '', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (7021, '定价修改', 'gateway:price:update', 3, 702, '', NULL, '', 2, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (7022, '定价删除', 'gateway:price:delete', 3, 702, '', NULL, '', 3, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (7030, '配额管理', 'gateway:quota:grant', 3, 703, '', NULL, '', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
