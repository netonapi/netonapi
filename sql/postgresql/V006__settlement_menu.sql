-- gateway V006: 结算待处理页的菜单与权限
-- 这些记录仍持有用户预留额度，需要运营能看到并裁定，否则预留会无声地卡住用户额度。

INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
VALUES (704, '结算待处理', 'gateway:settlement:list', 2, 7, 'settlement', 'gateway/settlement/index', 'ant-design:audit-outlined', 5, 1, 0, 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
VALUES (7040, '执行结算恢复', 'gateway:settlement:tick', 3, 704, '', NULL, '', 1, 1, 0, 0)
ON CONFLICT (id) DO NOTHING;
