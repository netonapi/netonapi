-- V028：去掉「用户专属分组」（原「用户计价例外」，gateway_group_overrides）
--
-- 给单个用户调价统一走「用户定价」（用户 × 模型的定价规则，docs/gateway-pricing-spec.md §4.3）；
-- 专线 / 特价分组由运营在「API 令牌」里直接指定到 Key 上。专属分组与这两者重复，且「开通了要等用户自己
-- 把 Key 换过去才生效」，运营以为给了优惠、客户却仍按原价计费。

-- 1. 已经绑到专属分组上的 Key（用户自选、且那个组并不对所有人开放）改成运营指定，计价保持不变：
--    专属分组没了之后，用户自选的这个组在调用时会被判成「不再对你开放」（403）。
UPDATE gateway_tokens t
   SET group_assigned = 1, updated_at = (extract(epoch from now()) * 1000)::bigint
 WHERE t.group_assigned = 0
   AND t.group_override IS NOT NULL AND t.group_override <> '' AND t.group_override <> 'default'
   AND EXISTS (SELECT 1 FROM gateway_group_overrides o WHERE o.user_id = t.user_id AND o.group_code = t.group_override)
   AND NOT EXISTS (SELECT 1 FROM gateway_groups g WHERE g.code = t.group_override AND g.selectable = 1 AND g.deleted = 0);

-- 2. 菜单与按钮权限（页面 + 查看 / 创建 / 修改 / 撤销）
DELETE FROM system_role_menus
 WHERE menu_id IN (SELECT id FROM system_menus
                    WHERE component = 'gateway/group-override/index' OR permission LIKE 'gateway:group-override:%'
                       OR permission LIKE 'gateway:group_override:%');
DELETE FROM system_menus
 WHERE component = 'gateway/group-override/index' OR permission LIKE 'gateway:group-override:%'
    OR permission LIKE 'gateway:group_override:%';

-- 后面的菜单前移一位，与「用户定价」(5) 之后的顺序保持连续
UPDATE system_menus SET sort = v.sort, updated_at = (extract(epoch from now()) * 1000)::bigint
  FROM (VALUES ('token', 6), ('group', 7), ('redemption', 8), ('settlement', 9)) AS v(path, sort)
 WHERE system_menus.type = 2 AND system_menus.path = v.path
   AND system_menus.parent_id = (SELECT id FROM system_menus WHERE name = 'AI 网关' AND type = 1 AND parent_id = 0);

-- 3. 表
DROP TABLE IF EXISTS gateway_group_overrides;
