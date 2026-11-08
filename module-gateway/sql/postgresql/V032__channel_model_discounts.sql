CREATE TABLE gateway_channel_model_discounts (
    id BIGSERIAL PRIMARY KEY,
    channel_id BIGINT NOT NULL REFERENCES gateway_channels(id) ON DELETE CASCADE,
    model TEXT NOT NULL,
    discount VARCHAR(32) NOT NULL,
    UNIQUE (channel_id, model)
);

-- 存量服务路由保留已有的实际成本配置。候选清单 supported_models 从未代表启用。
WITH active AS (
    SELECT channel.id, channel.cost_discount, model.request_name,
           COALESCE(NULLIF(btrim(mapping.value), ''), model.request_name) AS upstream_name
      FROM gateway_channels channel
     CROSS JOIN LATERAL (
         SELECT btrim(name) AS request_name FROM regexp_split_to_table(channel.models, ',') AS name
         UNION
         SELECT key FROM jsonb_each_text(CASE WHEN channel.model_mapping IS JSON OBJECT
                                              THEN channel.model_mapping::jsonb ELSE '{}'::jsonb END)
     ) model
      LEFT JOIN LATERAL jsonb_each_text(CASE WHEN channel.model_mapping IS JSON OBJECT
                                           THEN channel.model_mapping::jsonb ELSE '{}'::jsonb END) mapping
        ON mapping.key = model.request_name
     WHERE model.request_name <> ''
), normalized AS (
    INSERT INTO gateway_channel_model_discounts (channel_id, model, discount)
    SELECT DISTINCT id, upstream_name, cost_discount FROM active
    RETURNING channel_id
)
UPDATE gateway_channels channel
   SET models = enabled.models
  FROM (SELECT id, string_agg(request_name, ',' ORDER BY request_name) AS models FROM active GROUP BY id) enabled
 WHERE channel.id = enabled.id;
