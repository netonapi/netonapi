-- V005 补齐 gateway 权限的菜单种子
--
-- 为什么需要这一条：rbac-spec §1.4 冻结了「权限只能通过 菜单 → 角色 → 用户 继承」，明确禁止
-- User→Permission 与 Role→Permission 直连。所以**一个 @Permission 串如果没有对应的 system_menus
-- 行，它就无法被授予任何角色** —— 只有 super_admin 的 `*:*:*` 通配能过。到本迁移之前 gateway
-- 有 17 个带注解的权限串没有种子（计价组 5、兑换码 4、用户级计价例外 5，加 channel/price/quota
-- 三个 :get），也就是说这几套后台功能在任何部署上都只有超级管理员能用，委派不给运营和客服。
--
-- ── 只有 postgresql 方言，这是刻意的 ──
-- gateway 的 sql/mysql/ 与 sql/sqlite/ 是跑不到的死重：其余模块的 neton.migration.dialects
-- 都硬编码 postgresql，而 system_menus 只由 module-infra 的 postgresql V001 建表。详见
-- DEPLOY.md 的数据库一节。更要紧的是那两方言的 V001 仍在**写死菜单 id**，而 postgresql V001
-- 的注释记录了撞号事故：gateway 与 privchat 撞了 700-704，唯一的保护 `ON CONFLICT (id) DO
-- NOTHING` 不报错、只静默丢菜单，结果是网关的按钮被挂到了别的模块的菜单底下。给它们补一条
-- 写死 704 的 V005 就是原样重演那次事故，所以不补。
--
-- ── 父级一律用标量子查询，不用 JOIN ──
-- JOIN 在父菜单缺失时会静默插 0 行：迁移报成功、权限依旧不可授予，正是本迁移要消灭的那类失败。
-- 标量子查询缺失就返回 NULL，撞 parent_id NOT NULL 让整个脚本失败；同名父级多于一个则报
-- "more than one row returned by a subquery"。两种情况都响亮。（V001 用的 `LIMIT 1` 同样是个
-- 消音器：真出现两个同名根，它会随便挑一个。）
--
-- ── 不加 NOT EXISTS 之类的幂等守卫 ──
-- MigrationEngine 用 neton_schema_history 记账，已成功的脚本一律跳过；且 PostgreSQL 方言把整个
-- 脚本包在 db.transaction 里，半失败会整体回滚，重跑必然从干净状态开始。再加一层「按 permission
-- 跳过」只会多出一条静默不插行的路径。同理也不放 DO 块的条数自检：改成标量子查询后每行要么插入
-- 要么整条语句抛错，"插 0 行"只剩 VALUES 为空这一种可能，而写死 17 的检查会在下次加权限时过期。
-- 「每个 @Permission 都得有菜单种子」这个不变量交给 harness 动态比对注解集，不在这里写死。
--
-- ── 为什么分成两条语句 ──
-- 第 2 步的父菜单包含第 1 步刚插入的行，而同一条语句里数据修改型 CTE 的写入对其它部分不可见
-- （只能靠自己的 RETURNING）。拆开后第二条就能直接按名字查到。
--
-- ── component 是承重字段 ──
-- 三个二级菜单的 component 与 neton-application-front-gateway/src/manifest.ts 的 page key
-- 一一对应：前端 catch-all 就是拿 component 去 pageRegistry 里取 loader。菜单与页面必须同增量出
-- —— 少了页面，菜单点进去是「页面未安装」；少了菜单，页面根本进不去。
--
-- status 一律 1：前端 buildRouteMap/buildNav 都 `if (n.status !== 1) continue`，而列默认值是 0，
-- 漏写这一位菜单会既不进侧栏也不进路由表，且不报任何错。

-- 1) 三个二级菜单，挂在 V001 建的 'AI 网关' 目录下（sort 接在 V001 的 1-5 之后）
INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
SELECT v.name, v.permission, 2,
       (SELECT r.id FROM system_menus r WHERE r.name = 'AI 网关' AND r.type = 1 AND r.parent_id = 0),
       v.path, v.component, v.icon, v.sort, 1,
       (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint
FROM (VALUES
    ('计价组',       'gateway:group:list',          'group',          'gateway/group/index',          'ant-design:tags-outlined',        6),
    ('兑换码',       'gateway:redemption:list',     'redemption',     'gateway/redemption/index',     'ant-design:gift-outlined',        7),
    ('用户计价例外', 'gateway:group_override:list', 'group-override', 'gateway/group-override/index', 'ant-design:user-switch-outlined', 8)
) AS v(name, permission, path, component, icon, sort);

-- 2) 按钮级权限（type=3），path 空、component NULL，与 V001 的三级行同形。
--    父级三重限定 (name, type=2, 挂在 'AI 网关' 下)，避免撞上别的模块的同名菜单。
--    三个 :get 的归属按控制器核对过：channel:get→ChannelController、price:get→PriceController、
--    quota:get→QuotaController（配额发放本来就挂在「用量日志」下，查看与它同页）。
INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
SELECT v.name, v.permission, 3,
       (SELECT p.id FROM system_menus p
         WHERE p.name = v.parent_name AND p.type = 2
           AND p.parent_id = (SELECT r.id FROM system_menus r
                               WHERE r.name = 'AI 网关' AND r.type = 1 AND r.parent_id = 0)),
       '', NULL, '', v.sort, 1,
       (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint
FROM (VALUES
    ('渠道查看',     'gateway:channel:get',           '上游渠道',     4),
    ('定价查看',     'gateway:price:get',             '模型定价',     4),
    ('配额查看',     'gateway:quota:get',             '用量日志',     2),
    ('计价组查看',   'gateway:group:get',             '计价组',       1),
    ('计价组创建',   'gateway:group:create',          '计价组',       2),
    ('计价组修改',   'gateway:group:update',          '计价组',       3),
    ('计价组删除',   'gateway:group:delete',          '计价组',       4),
    ('批次核销情况', 'gateway:redemption:get',        '兑换码',       1),
    ('兑换码生成',   'gateway:redemption:create',     '兑换码',       2),
    ('兑换码作废',   'gateway:redemption:disable',    '兑换码',       3),
    ('例外查看',     'gateway:group_override:get',    '用户计价例外', 1),
    ('例外创建',     'gateway:group_override:create', '用户计价例外', 2),
    ('例外修改',     'gateway:group_override:update', '用户计价例外', 3),
    ('例外撤销',     'gateway:group_override:delete', '用户计价例外', 4)
) AS v(name, permission, parent_name, sort);
