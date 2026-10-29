-- V009：管理台「网关概览」菜单
--
-- 页面权限复用 gateway:log:list（数字全部从用量日志算出，看得了明细就看得了汇总），所以不新增
-- 权限串、不需要按钮级种子；只补一条 type=2 的页面行让它出现在侧栏与路由表。sort=0 排在渠道前面：
-- 运营早上打开管理台第一眼要看的就是它。其余取舍同 V005/V006（父级用标量子查询、status 写 1）。
INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
SELECT '网关概览', 'gateway:log:list', 2,
       (SELECT r.id FROM system_menus r WHERE r.name = 'AI 网关' AND r.type = 1 AND r.parent_id = 0),
       'overview', 'gateway/overview/index', 'ant-design:dashboard-outlined', 0, 1,
       (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint;
