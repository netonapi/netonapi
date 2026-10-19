-- V018：调价记录。官方价、售价设置的每次变化都留一行，回答「这个模型什么时候、谁、从多少改成了多少」。
-- action：create / update / delete / import / sync / calibrate；before / after 是价格字段的 JSON（新增时 before 为空，删除时 after 为空）。
CREATE TABLE IF NOT EXISTS gateway_price_history (
    id          BIGSERIAL PRIMARY KEY,
    model       VARCHAR(128) NOT NULL,
    action      VARCHAR(16)  NOT NULL,
    before      TEXT,
    after       TEXT,
    operator_id BIGINT,
    created_at  BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_gateway_price_history_model ON gateway_price_history (model, created_at DESC);
