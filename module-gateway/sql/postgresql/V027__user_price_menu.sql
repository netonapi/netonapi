-- V027：「用户定价」页面（docs/gateway-pricing-spec.md §4.3）
--
-- 按用户查他在各计费分组下的价目表，并给他设定价规则（官方价折扣 / 固定价 / 开放）。接口与「模型定价」同一组权限
-- （gateway:price:query / update），所以页面也挂 gateway:price:query；排在「模型定价」旁边。
INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
SELECT '用户定价', 'gateway:price:query', 2,
       (SELECT r.id FROM system_menus r WHERE r.name = 'AI 网关' AND r.type = 1 AND r.parent_id = 0),
       'user-price', 'gateway/user-price/index', 'ant-design:solution-outlined', 5, 1,
       (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint
 WHERE EXISTS (SELECT 1 FROM system_menus r WHERE r.name = 'AI 网关' AND r.type = 1 AND r.parent_id = 0)
   AND NOT EXISTS (SELECT 1 FROM system_menus WHERE component = 'gateway/user-price/index');

-- 紧跟「模型定价」(sort 4)：其后的菜单顺延一位（V012 的使用频率顺序不变）
UPDATE system_menus SET sort = v.sort, updated_at = (extract(epoch from now()) * 1000)::bigint
  FROM (VALUES ('token', 6), ('group', 7), ('group-override', 8), ('redemption', 9), ('settlement', 10)) AS v(path, sort)
 WHERE system_menus.type = 2 AND system_menus.path = v.path
   AND system_menus.parent_id = (SELECT id FROM system_menus WHERE name = 'AI 网关' AND type = 1 AND parent_id = 0);

-- 网关运营角色（存在时）一并授予
INSERT INTO system_role_menus (role_id, menu_id, created_at)
SELECT (SELECT id FROM system_roles WHERE code = 'gateway_ops'), m.id, (extract(epoch from now()) * 1000)::bigint
  FROM system_menus m
 WHERE m.component = 'gateway/user-price/index'
   AND EXISTS (SELECT 1 FROM system_roles WHERE code = 'gateway_ops')
   AND NOT EXISTS (SELECT 1 FROM system_role_menus rm
                    WHERE rm.role_id = (SELECT id FROM system_roles WHERE code = 'gateway_ops') AND rm.menu_id = m.id);
