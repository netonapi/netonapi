-- gateway module V001: 渠道与多 Key
CREATE TABLE gateway_channels (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    type VARCHAR(32) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    groups VARCHAR(256) NOT NULL DEFAULT 'default',
    models TEXT NOT NULL DEFAULT '',
    model_mapping TEXT,
    param_override TEXT,
    priority INT NOT NULL DEFAULT 0,
    weight INT NOT NULL DEFAULT 1,
    status INT NOT NULL DEFAULT 1,
    ttfb_timeout_ms BIGINT NOT NULL DEFAULT 30000,
    idle_timeout_ms BIGINT NOT NULL DEFAULT 90000,
    proxy_url VARCHAR(512),
    cost_discount DECIMAL(10,4) NOT NULL DEFAULT 1.0,
    remark VARCHAR(512),
    deleted INT NOT NULL DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT
);
CREATE INDEX idx_gateway_channels_status ON gateway_channels(status, deleted);

CREATE TABLE gateway_channel_keys (
    id BIGSERIAL PRIMARY KEY,
    channel_id BIGINT NOT NULL,
    api_key VARCHAR(1024) NOT NULL,
    status INT NOT NULL DEFAULT 1,
    fail_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(512),
    last_used_at BIGINT,
    deleted INT NOT NULL DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT
);
CREATE INDEX idx_gateway_channel_keys_channel ON gateway_channel_keys(channel_id, status, deleted);
