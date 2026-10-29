-- 计费分组只由 API Key 的配置决定（docs/key-billing-group-design.md）
ALTER TABLE gateway_groups ADD COLUMN IF NOT EXISTS selectable SMALLINT NOT NULL DEFAULT 0;
UPDATE gateway_groups SET selectable = 1 WHERE code = 'default';

ALTER TABLE gateway_tokens ADD COLUMN IF NOT EXISTS group_assigned SMALLINT NOT NULL DEFAULT 0;
-- 此前 Key 上的分组只能由运营设：保持为「运营指定」
UPDATE gateway_tokens SET group_assigned = 1 WHERE group_override IS NOT NULL AND group_override <> '';
-- 此前用户例外作用于这个用户的所有 Key：把它落到他还没绑分组的 Key 上（之后用户例外只表示「额外可选」）
UPDATE gateway_tokens t SET group_override = o.group_code
  FROM gateway_group_overrides o
 WHERE o.user_id = t.user_id AND (t.group_override IS NULL OR t.group_override = '') AND t.deleted = 0;
