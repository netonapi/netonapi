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
