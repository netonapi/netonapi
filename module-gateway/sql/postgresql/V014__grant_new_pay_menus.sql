-- V014：把 payment V003 新补的支付渠道页与写按钮授给「财务」角色
--
-- V012 建四个预置角色时，支付渠道还没有菜单行（它那时只能从一个 404 的页面进去），
-- 所以财务角色里没有它。payment V003 补上菜单之后，这里补授权 —— 配收款通道本来就是财务的活。
-- 放在 gateway 是因为预置角色是本发行版的产物（V012 建的），不是 canonical 的事。
INSERT INTO system_role_menus (role_id, menu_id, created_at)
SELECT (SELECT id FROM system_roles WHERE code = 'finance'), m.id, (extract(epoch from now()) * 1000)::bigint
  FROM system_menus m
 WHERE m.status = 1
   AND m.permission LIKE 'pay:channel:%'
   AND EXISTS (SELECT 1 FROM system_roles WHERE code = 'finance')
   AND NOT EXISTS (
        SELECT 1 FROM system_role_menus rm
         WHERE rm.role_id = (SELECT id FROM system_roles WHERE code = 'finance') AND rm.menu_id = m.id);

-- 只读角色同样跟上（它拿的是业务三组的全部页面，type<=2）
INSERT INTO system_role_menus (role_id, menu_id, created_at)
SELECT (SELECT id FROM system_roles WHERE code = 'readonly'), m.id, (extract(epoch from now()) * 1000)::bigint
  FROM system_menus m
 WHERE m.status = 1 AND m.type = 2 AND m.permission = 'pay:channel:query'
   AND EXISTS (SELECT 1 FROM system_roles WHERE code = 'readonly')
   AND NOT EXISTS (
        SELECT 1 FROM system_role_menus rm
         WHERE rm.role_id = (SELECT id FROM system_roles WHERE code = 'readonly') AND rm.menu_id = m.id);
