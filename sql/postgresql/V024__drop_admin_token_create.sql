-- 后台不再能代用户创建 API Key（只能用户本人在控制台建），撤掉对应的权限按钮
DELETE FROM system_role_menus WHERE menu_id IN (SELECT id FROM system_menus WHERE permission = 'gateway:token:create');
DELETE FROM system_menus WHERE permission = 'gateway:token:create';
