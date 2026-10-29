-- V021：对外模型的展示名（如「Opus 5.5」）。模型 ID 仍是调用时用的 model 字段；展示名只用于价格页、控制台与公开价格页。
ALTER TABLE gateway_model_prices ADD COLUMN IF NOT EXISTS display_name VARCHAR(64);
