-- V023：对外模型目录（netonapi-backend/docs/model-catalog-design.md）。
-- 对外提供哪些模型只由我们精选的目录决定；上游只是线路，支持什么不让任何模型自动对外。

-- 价格行即目录项：listed = 1 才对外（用户可见、可调用）。已有行是从价源导入 / 校准来的价格库，一律先下架，
-- 由运营挑出要卖的模型再上架。新建行的缺省是 1（管理台手动添加 = 要卖）；导入 / 校准在代码里显式写 0。
ALTER TABLE gateway_model_prices ADD COLUMN IF NOT EXISTS listed SMALLINT NOT NULL DEFAULT 1;
UPDATE gateway_model_prices SET listed = 0;

-- 渠道的「支持的模型」：「拉取模型」只写这里。已有的 models 多半是拉来的整张清单，挪进来作为候选；
-- 服务清单与映射清空，由模型目录重新指定每个模型的服务上游。
ALTER TABLE gateway_channels ADD COLUMN IF NOT EXISTS supported_models TEXT NOT NULL DEFAULT '';
UPDATE gateway_channels SET supported_models = models, models = '', model_mapping = NULL WHERE deleted = 0;
