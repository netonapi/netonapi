-- gateway 模块（mysql）—— 1.0.0 beta1 合并基线。
--
-- 🔴 **只给全新数据库用。** 由原来的 3 个迁移脚本按执行顺序拼接而成：
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
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    type VARCHAR(32) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    `groups` VARCHAR(256) NOT NULL DEFAULT 'default',
    -- VARCHAR 而非 TEXT：MySQL 不允许 BLOB/TEXT 列带 DEFAULT，用 VARCHAR 保住与 PG 相同的 NOT NULL DEFAULT '' 语义
    models VARCHAR(4096) NOT NULL DEFAULT '',
    model_mapping TEXT,
    param_override TEXT,
    priority INT NOT NULL DEFAULT 0,
    weight INT NOT NULL DEFAULT 1,
    status INT NOT NULL DEFAULT 1,
    ttfb_timeout_ms BIGINT NOT NULL DEFAULT 30000,
    idle_timeout_ms BIGINT NOT NULL DEFAULT 90000,
    proxy_url VARCHAR(512),
    cost_discount VARCHAR(32) NOT NULL DEFAULT '1.0',
    remark VARCHAR(512),
    deleted INT NOT NULL DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_gateway_channels_status ON gateway_channels(status, deleted);

CREATE TABLE gateway_channel_keys (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    channel_id BIGINT NOT NULL,
    api_key VARCHAR(1024) NOT NULL,
    status INT NOT NULL DEFAULT 1,
    fail_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(512),
    last_used_at BIGINT,
    deleted INT NOT NULL DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_gateway_channel_keys_channel ON gateway_channel_keys(channel_id, status, deleted);

CREATE TABLE gateway_tokens (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    key_display VARCHAR(32) NOT NULL,
    quota_budget BIGINT,
    quota_used BIGINT NOT NULL DEFAULT 0,
    expires_at BIGINT,
    allowed_models VARCHAR(4096) NOT NULL DEFAULT '',
    allowed_ips VARCHAR(2048) NOT NULL DEFAULT '',
    group_override VARCHAR(64),
    status INT NOT NULL DEFAULT 1,
    deleted INT NOT NULL DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE UNIQUE INDEX uk_gateway_tokens_hash ON gateway_tokens(key_hash);
CREATE INDEX idx_gateway_tokens_user ON gateway_tokens(user_id, deleted);

CREATE TABLE gateway_quota_accounts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    balance BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE UNIQUE INDEX uk_gateway_quota_accounts_user ON gateway_quota_accounts(user_id);

CREATE TABLE gateway_quota_transactions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    type VARCHAR(16) NOT NULL,
    amount BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    ref VARCHAR(128),
    created_at BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_gateway_quota_tx_user ON gateway_quota_transactions(user_id, created_at);

CREATE TABLE gateway_model_prices (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    model VARCHAR(128) NOT NULL,
    input_price VARCHAR(32) NOT NULL DEFAULT '0',
    output_price VARCHAR(32) NOT NULL DEFAULT '0',
    cache_read_price VARCHAR(32) NOT NULL DEFAULT '0',
    cache_write_price VARCHAR(32) NOT NULL DEFAULT '0',
    per_request_price BIGINT,
    sale_override TEXT,
    source VARCHAR(16) NOT NULL DEFAULT 'manual',
    deleted INT NOT NULL DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE UNIQUE INDEX uk_gateway_model_prices_model ON gateway_model_prices(model);

CREATE TABLE gateway_groups (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    ratio VARCHAR(32) NOT NULL DEFAULT '1.0',
    description VARCHAR(256),
    deleted INT NOT NULL DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE gateway_usage_logs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_id BIGINT NOT NULL,
    channel_id BIGINT NOT NULL,
    request_model VARCHAR(128) NOT NULL,
    upstream_model VARCHAR(128) NOT NULL,
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    cache_read_tokens INT NOT NULL DEFAULT 0,
    cache_write_tokens INT NOT NULL DEFAULT 0,
    charged BIGINT NOT NULL DEFAULT 0,
    cost BIGINT NOT NULL DEFAULT 0,
    ttfb_ms BIGINT,
    latency_ms BIGINT,
    stream INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ok',
    error_code VARCHAR(64),
    created_at BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_gateway_usage_logs_user ON gateway_usage_logs(user_id, created_at);
CREATE INDEX idx_gateway_usage_logs_channel ON gateway_usage_logs(channel_id, created_at);

-- 种子：默认分组
INSERT INTO gateway_groups (name, ratio, description, created_at) VALUES ('default', '1.0', '默认分组', 0);


-- ─────────────────────────────────────────────────────────────
-- 原 V002__seed_menus.sql
-- ─────────────────────────────────────────────────────────────

-- gateway 后台菜单（父菜单 id=7，子项 700-703；权限码对齐 controller）
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (7, 'AI 网关', '', 1, 0, '/gateway', NULL, 'ant-design:api-outlined', 7, 1, 0, 0) ON DUPLICATE KEY UPDATE id=id;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (700, '上游渠道', 'gateway:channel:list', 2, 7, 'channel', 'gateway/channel/index', 'ant-design:cluster-outlined', 1, 1, 0, 0) ON DUPLICATE KEY UPDATE id=id;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (701, 'API 令牌', 'gateway:token:list', 2, 7, 'token', 'gateway/token/index', 'ant-design:key-outlined', 2, 1, 0, 0) ON DUPLICATE KEY UPDATE id=id;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (702, '模型定价', 'gateway:price:list', 2, 7, 'price', 'gateway/price/index', 'ant-design:dollar-outlined', 3, 1, 0, 0) ON DUPLICATE KEY UPDATE id=id;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (703, '用量日志', 'gateway:log:list', 2, 7, 'log', 'gateway/log/index', 'ant-design:file-text-outlined', 4, 1, 0, 0) ON DUPLICATE KEY UPDATE id=id;

-- 按钮级权限（type=3，隶属各列表菜单）
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (7000, '渠道创建', 'gateway:channel:create', 3, 700, '', NULL, '', 1, 1, 0, 0) ON DUPLICATE KEY UPDATE id=id;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (7001, '渠道修改', 'gateway:channel:update', 3, 700, '', NULL, '', 2, 1, 0, 0) ON DUPLICATE KEY UPDATE id=id;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (7002, '渠道删除', 'gateway:channel:delete', 3, 700, '', NULL, '', 3, 1, 0, 0) ON DUPLICATE KEY UPDATE id=id;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (7010, '令牌创建', 'gateway:token:create', 3, 701, '', NULL, '', 1, 1, 0, 0) ON DUPLICATE KEY UPDATE id=id;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (7011, '令牌管理', 'gateway:token:update', 3, 701, '', NULL, '', 2, 1, 0, 0) ON DUPLICATE KEY UPDATE id=id;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (7012, '令牌删除', 'gateway:token:delete', 3, 701, '', NULL, '', 3, 1, 0, 0) ON DUPLICATE KEY UPDATE id=id;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (7020, '定价创建', 'gateway:price:create', 3, 702, '', NULL, '', 1, 1, 0, 0) ON DUPLICATE KEY UPDATE id=id;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (7021, '定价修改', 'gateway:price:update', 3, 702, '', NULL, '', 2, 1, 0, 0) ON DUPLICATE KEY UPDATE id=id;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (7022, '定价删除', 'gateway:price:delete', 3, 702, '', NULL, '', 3, 1, 0, 0) ON DUPLICATE KEY UPDATE id=id;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (7030, '配额管理', 'gateway:quota:grant', 3, 703, '', NULL, '', 1, 1, 0, 0) ON DUPLICATE KEY UPDATE id=id;


-- ─────────────────────────────────────────────────────────────
-- 原 V003__reliability.sql
-- ─────────────────────────────────────────────────────────────

-- gateway V003: 账务可靠性收口——结算幂等、渠道 429 冷却（MySQL；唯一索引允许多个 NULL，无需部分索引）

-- 升级已有库：建唯一索引前先校验存量无重复 ref，有重复则中止迁移并要求人工对账（与 PG 侧 DO 块对等）。
-- MySQL 无 DO 块：用「标量子查询返回多行」强制报错（ERROR 1242）中止；无重复时 IF 短路返回 'ok'。
SELECT IF(
    (SELECT COUNT(*) FROM (
        SELECT ref FROM gateway_quota_transactions WHERE ref IS NOT NULL GROUP BY ref HAVING COUNT(*) > 1
    ) d) > 0,
    (SELECT 'DUPLICATE ref in gateway_quota_transactions: reconcile before uk_gateway_quota_tx_ref'
     UNION ALL SELECT 'abort'),
    'ok'
) AS duplicate_ref_precheck;

CREATE UNIQUE INDEX uk_gateway_quota_tx_ref ON gateway_quota_transactions(ref);

ALTER TABLE gateway_channels ADD COLUMN cooldown_until BIGINT NOT NULL DEFAULT 0;
