-- 权限动作词统一（RBAC spec §3.4 冻结词汇表）：读一律 query；资源段与路由一致用连字符。
--
-- gateway 原先用 list 表达「列表页」、query 表达「单条」，与 system 的单一 query 不一致；
-- 同时 group_override 的资源段用了下划线，而它的路由是 /gateway/group-override，两边对不上。
-- V012 已经把 :get 改成 :query，这里收掉最后两类偏离。合并语义见 module-payment 的同名迁移。
UPDATE system_menus
   SET permission = regexp_replace(permission, '^(gateway:[a-z0-9_-]+):list$', '\1:query'),
       updated_at = (extract(epoch from now()) * 1000)::bigint
 WHERE permission ~ '^gateway:[a-z0-9_-]+:list$';

UPDATE system_menus
   SET permission = replace(permission, 'gateway:group_override:', 'gateway:group-override:'),
       updated_at = (extract(epoch from now()) * 1000)::bigint
 WHERE permission LIKE 'gateway:group_override:%';

-- ── 撤回 V012 里补的「支付回调」入口，并停用「应用管理」──
-- 两页都是**前端有页面、后端没有接口**：front-payment 里有 pay/notify/index 与 pay/app/index，
-- 但 module-payment 的 admin 控制器只有 /pay/channel /pay/order /pay/refund /pay/transfer
-- /pay/wallet* /wallet/*，实测 /admin/pay/notify/page 与 /admin/pay/app/page 都是 404。
--
-- V012 补支付回调入口时只核对了「前端页面存在」就下了结论，没验后端端点 —— 这里纠正。
-- 判断一个页面是否该进侧栏，标准是**后端有没有对应的 @Permission 与路由**，不是前端有没有 .tsx。
-- 停用而不删除：哪天 payment 补上控制器，在「菜单管理」里打开即可。
UPDATE system_menus
   SET status = 0,
       -- 停用归停用，权限串也要改规范：这一行将来若被重新启用，串必须已经是对的，
       -- 否则就成了一条「停用状态下藏着的不合规数据」，下次全量 lint 又会抓到它。
       permission = regexp_replace(permission, '^(pay:[a-z0-9-]+):(page|list|detail|overview)$', '\1:query'),
       updated_at = (extract(epoch from now()) * 1000)::bigint
 WHERE component IN ('pay/notify/index', 'pay/app/index');
