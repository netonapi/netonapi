-- V006 给额度撤回补菜单种子
--
-- 为什么需要这一条：rbac-spec §1.4 冻结了「权限只能通过 菜单 → 角色 → 用户 继承」，一个
-- @Permission 串没有对应的 system_menus 行就无法被授予任何角色，只有 super_admin 的 `*:*:*`
-- 能过。gateway:quota:revoke 是本轮新增的（POST /admin/gateway/quota/revoke），不补种子的话
-- 「撤回误发的额度」就只有超级管理员能做 —— 而这件事恰恰是运营日常纠错，不该每次都升级到超管。
--
-- ── 挂在「用量日志」下，不新建二级菜单，这是被迫的而不是随意的 ──
-- gateway:quota:grant 的种子在 V001（'配额管理'，sort=1）、gateway:quota:get 的种子在 V005
-- （'配额查看'，sort=2），两者都已挂在 '用量日志' 这个二级菜单下。已应用的脚本不能改（改一个
-- 字节就是 checksum mismatch，引擎会让库拒绝启动，见 DEPLOY.md §升级），所以这两个权限挪不走。
-- 若在这里新建一个 '额度运维' 二级菜单，同一套额度运维的三个权限就会分散在两个菜单节点下，
-- 运营授权时要在两处勾，漏一处就是「能撤回但看不见余额」。所以宁可让菜单名与内容不匹配
-- （'用量日志' 下挂着发放与撤回），也不要让一套权限分两处 —— 后者会静默地授出一半能力。
-- 菜单改名是 DML，将来可以单独一条迁移做（UPDATE system_menus SET name=...），它不改已应用
-- 的脚本、因此不触发 checksum mismatch；本轮不做，因为它会动到运营已经熟悉的侧栏文字。
--
-- 其余取舍与 V005 完全一致，理由也一致：只有 postgresql 方言；父级用标量子查询而不是 JOIN
-- （JOIN 在父菜单缺失时静默插 0 行，迁移报成功而权限依旧不可授予）；不加 NOT EXISTS 幂等守卫
-- （MigrationEngine 已按 neton_schema_history 跳过成功脚本，且整个脚本在 db.transaction 里，
-- 半失败整体回滚）；status 一律写 1（列默认值是 0，而前端 buildRouteMap/buildNav 都
-- `if (n.status !== 1) continue`，漏写这一位菜单会既不进侧栏也不进路由表且不报任何错）。
-- 按钮级权限（type=3）path 空、component NULL，与 V001/V005 的三级行同形。

INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
SELECT '额度撤回', 'gateway:quota:revoke', 3,
       (SELECT p.id FROM system_menus p
         WHERE p.name = '用量日志' AND p.type = 2
           AND p.parent_id = (SELECT r.id FROM system_menus r
                               WHERE r.name = 'AI 网关' AND r.type = 1 AND r.parent_id = 0)),
       '', NULL, '', 3, 1,
       (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint;
