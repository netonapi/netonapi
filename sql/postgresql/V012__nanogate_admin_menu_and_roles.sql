-- V012：NanoGate 发行版的管理台信息架构 —— 菜单裁剪、重排、预置角色
--
-- ── 为什么这份 DML 落在 module-gateway ──
-- NanoGate 是唯一装配 gateway 的发行版（canonical 脚手架 neton-application 装的是 cs，不是 gateway），
-- 所以 module-gateway 就是本产品的领域模块，「本产品的管理台长什么样」是它的职责。
-- 同样的取舍已有先例：V005 / V006 / V009 都在往 system_menus 写自己的菜单行。
-- 反过来说，这些裁剪**绝不能**写进 module-system / module-infra / module-member —— 那些是
-- canonical 模块，privchat 等产品要用签到、提现、开放平台，删了别人的菜单就是越界。
-- 迁移顺序上 gateway 排在最后（system → infra → member → payment → platform → gateway），
-- 因此这里能看到前面所有模块已经种好的菜单行。
--
-- ── 为什么是 status=0 而不是 DELETE ──
-- ENGINEERING_RULES §6.3 要求「删除目录、API、菜单和依赖，而不是只隐藏入口」，那一条针对的是
-- **前端模板残留**（对应的动作是把 front-platform 从 front.modules.ts 摘掉，本轮已做）。
-- 菜单行是运营数据不是模板：DELETE 之后运营在「菜单管理」里再也找不回来，而 status=0 在那张表里
-- 看得见、可以自己打开。RBAC spec §5.3 也保证 status≠1 的菜单不进树、其 permission 不进权限集、
-- 子菜单整体丢弃 —— 语义上与「不存在」等价，但可逆。

-- ── 1. 权限动词对齐 RBAC spec §3.4（query = 单条查询/详情；get 不在词汇表内）──
UPDATE system_menus SET permission = regexp_replace(permission, '^(gateway:[a-z_]+):get$', '\1:query'), updated_at = (extract(epoch from now()) * 1000)::bigint
 WHERE permission ~ '^gateway:[a-z_]+:get$';

-- ── 2. 顶级目录重排：产品核心排第一屏 ──
-- 原顺序是脚手架的通用后台顺序（系统管理 1 → … → AI 网关 7），运营每天要用的 9 个页面排在最后，
-- 每次都要滚到侧栏底部。这里按「日常使用频率」重排。
UPDATE system_menus SET sort = CASE name
        WHEN 'AI 网关'  THEN 1
        WHEN '会员中心' THEN 2
        WHEN '支付中心' THEN 3
        WHEN '系统管理' THEN 4
        WHEN '基础设施' THEN 5
        ELSE sort END,
    updated_at = (extract(epoch from now()) * 1000)::bigint
 WHERE type = 1 AND parent_id = 0 AND name IN ('AI 网关','会员中心','支付中心','系统管理','基础设施');

-- AI 网关内部按使用频率重排：看盘 → 排障 → 配置 → 销售 → 兜底
UPDATE system_menus SET sort = v.sort, updated_at = (extract(epoch from now()) * 1000)::bigint
  FROM (VALUES
    ('overview', 1), ('log', 2), ('channel', 3), ('price', 4), ('token', 5),
    ('group', 6), ('group-override', 7), ('redemption', 8), ('settlement', 9)
  ) AS v(path, sort)
 WHERE system_menus.type = 2 AND system_menus.path = v.path
   AND system_menus.parent_id = (SELECT id FROM system_menus WHERE name = 'AI 网关' AND type = 1 AND parent_id = 0);

-- ── 3. 停用与本产品无关的页面 ──
-- 开放平台整组：API 管理 / 客户端 / 计费记录 / 调用日志 / 统计分析 与 AI 网关职责直接重叠，
-- 两套「调用日志」并存会让运营不知道该查哪个。目录停用后子菜单按 §5.3 整体丢弃。
-- 目录与其下页面都显式停用：只停目录的话，RBAC spec §5.3 保证它不进菜单树，但
-- PermissionLogic 收集权限时只看菜单行自己的 status，子页面的 permission 仍会进权限集。
-- 对已经授过 platform:* 的角色来说那就是「看不见入口却调得通接口」。
UPDATE system_menus SET status = 0, updated_at = (extract(epoch from now()) * 1000)::bigint
 WHERE (type = 1 AND parent_id = 0 AND name = '开放平台') OR permission LIKE 'platform:%';

-- 会员域的电商/社区功能：签到、积分、等级、标签、分组。中转站的会员只有「余额 + 令牌」。
-- 「消息中心」的三页是**坏的**，不只是无关：它们的 permission 是 system:message-*，
-- 而它们指向的页面（system/sms/*）实际调用的是 /system/sms-* 接口（权限 system:sms-*）。
-- 授了「消息渠道」的角色能看见入口、点进去 403。同一批页面由「短信管理」正确覆盖，故停用前者。
-- 社交用户：控制台不提供 Google / Telegram 登录，这页永远是空的。
-- 文件管理 / 文件配置：中转站不存用户文件。
-- 转账 / 提现 / 冻结：这三块是给「要往外付款」的平台用的，中转站只收款。
UPDATE system_menus SET status = 0, updated_at = (extract(epoch from now()) * 1000)::bigint
 WHERE permission IN (
        'member:signin:query', 'member:point:query', 'member:level:query',
        'member:tag:query', 'member:group:query',
        'system:message-channel:query', 'system:message-template:query', 'system:message-log:query',
        'system:social-user:query',
        'infra:file:query', 'infra:file-config:query',
        'pay:transfer:list', 'pay:withdraw:list', 'pay:wallet-freeze:menu'
      );
-- 站内信：通知模板 / 通知消息是发给 C 端的站内消息，而用户控制台没有任何展示面，
-- 发了也没人看得到。管理台留着两页只会让人以为这个能力是通的。
UPDATE system_menus SET status = 0, updated_at = (extract(epoch from now()) * 1000)::bigint
 WHERE permission IN ('system:notify-template:query', 'system:notify-message:query');

-- 「消息中心」「站内信」两个目录本身（无 permission，按名字定位）
UPDATE system_menus SET status = 0, updated_at = (extract(epoch from now()) * 1000)::bigint
 WHERE type = 1 AND name IN ('消息中心', '站内信');

-- 部门 / 岗位 / 字典**刻意保留**：用户表单的部门选择器会拉 /admin/system/dept/simple-list，
-- 停用部门菜单会让 system:dept:query 从权限集里消失，非超管建用户时选择器就空了。
-- 两页的噪音远小于「建不了用户」的代价。

-- ── 4. 支付中心扁平化 + 补回一个藏起来的页面 ──
-- 「钱包管理」是三级目录（支付中心 > 钱包管理 > 钱包余额），停掉冻结之后只剩两页，
-- 为两页保留一层目录等于每次多点一下。把两页提到支付中心下，目录停用。
UPDATE system_menus SET parent_id = (SELECT id FROM system_menus WHERE name = '支付中心' AND type = 1 AND parent_id = 0),
       sort = CASE permission WHEN 'pay:wallet:list' THEN 6 ELSE 7 END,
       updated_at = (extract(epoch from now()) * 1000)::bigint
 WHERE permission IN ('pay:wallet:list', 'pay:wallet-recharge-package:list');
UPDATE system_menus SET status = 0, updated_at = (extract(epoch from now()) * 1000)::bigint
 WHERE type = 1 AND name = '钱包管理';

-- 支付回调记录：页面一直在（pay/notify/index），但从来没有菜单入口 ——
-- 而「用户付了钱额度没到账」正是要先看这一页。补上。
INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
SELECT '支付回调', 'pay:notify:page', 2,
       (SELECT id FROM system_menus WHERE name = '支付中心' AND type = 1 AND parent_id = 0),
       'notify', 'pay/notify/index', 'ant-design:api-outlined', 5, 1,
       (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint
 WHERE NOT EXISTS (SELECT 1 FROM system_menus WHERE component = 'pay/notify/index');

-- ── 5. 预置角色 ──
-- 在此之前全库只有 super_admin 一个角色，94 项菜单全给它。结果是新人入职只有两个选择：
-- 发超管，或者在授权页面手工勾 94 项。权限体系建好了却没人用得起来，这是最大的安全缺口。
-- 四个角色覆盖中转站的真实分工；粒度按「这个岗位需要点哪些按钮」定，不按模块切。
-- baseline 用显式 id 插了 super_admin（role_menus 同理），序列因此还停在初始值，
-- 不先推一把这里的无 id INSERT 会直接撞 pkey。对已经正常的库这两行是幂等的空操作。
SELECT setval(pg_get_serial_sequence('system_roles', 'id'), GREATEST((SELECT COALESCE(MAX(id), 1) FROM system_roles), 1));
SELECT setval(pg_get_serial_sequence('system_role_menus', 'id'), GREATEST((SELECT COALESCE(MAX(id), 1) FROM system_role_menus), 1));
INSERT INTO system_roles (code, name, description, sort, status, created_at, updated_at)
SELECT v.code, v.name, v.description, v.sort, 1,
       (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint
  FROM (VALUES
    ('gateway_ops', '网关运营', '上游渠道、模型、令牌与用量：接入新上游、排查中转故障。不含定价与财务。', 1),
    ('finance',     '财务',     '定价、计价组、兑换码、充值与结算复核。不碰上游渠道配置。', 2),
    ('support',     '客服',     '查用量、查会员、发放与撤回额度、查订单。只读为主，唯一的写是额度调整。', 3),
    ('readonly',    '只读',     '业务侧全部页面只读，不含任何写操作，也不含系统管理与基础设施。', 4)
  ) AS v(code, name, description, sort)
 WHERE NOT EXISTS (SELECT 1 FROM system_roles r WHERE r.code = v.code);

-- 授权：按 permission 精确挑菜单行。目录（type=1）必须一起给，否则子页面在侧栏里没有落点。
-- 网关运营
INSERT INTO system_role_menus (role_id, menu_id, created_at)
SELECT (SELECT id FROM system_roles WHERE code = 'gateway_ops'), m.id, (extract(epoch from now()) * 1000)::bigint
  FROM system_menus m
 WHERE m.status = 1
   AND ( (m.type = 1 AND m.name IN ('AI 网关', '会员中心'))
      OR m.permission LIKE 'gateway:channel:%' OR m.permission LIKE 'gateway:token:%'
      OR m.permission LIKE 'gateway:log:%'     OR m.permission LIKE 'gateway:quota:query'
      OR m.permission = 'member:user:query' )
   AND NOT EXISTS (SELECT 1 FROM system_role_menus rm WHERE rm.role_id = (SELECT id FROM system_roles WHERE code = 'gateway_ops') AND rm.menu_id = m.id);

-- 财务
INSERT INTO system_role_menus (role_id, menu_id, created_at)
SELECT (SELECT id FROM system_roles WHERE code = 'finance'), m.id, (extract(epoch from now()) * 1000)::bigint
  FROM system_menus m
 WHERE m.status = 1
   AND ( (m.type = 1 AND m.name IN ('AI 网关', '支付中心'))
      OR m.permission LIKE 'gateway:price:%'      OR m.permission LIKE 'gateway:group:%'
      OR m.permission LIKE 'gateway:group_override:%' OR m.permission LIKE 'gateway:redemption:%'
      OR m.permission LIKE 'gateway:settlement:%' OR m.permission LIKE 'gateway:quota:%'
      OR m.permission LIKE 'gateway:log:%'
      OR m.permission IN ('pay:order:list', 'pay:refund:list', 'pay:notify:page', 'pay:app:list',
                          'pay:wallet:list', 'pay:wallet-recharge-package:list') )
   AND NOT EXISTS (SELECT 1 FROM system_role_menus rm WHERE rm.role_id = (SELECT id FROM system_roles WHERE code = 'finance') AND rm.menu_id = m.id);

-- 客服：唯一的写权限是额度发放/撤回（客诉补偿），其余只读
INSERT INTO system_role_menus (role_id, menu_id, created_at)
SELECT (SELECT id FROM system_roles WHERE code = 'support'), m.id, (extract(epoch from now()) * 1000)::bigint
  FROM system_menus m
 WHERE m.status = 1
   AND ( (m.type = 1 AND m.name IN ('AI 网关', '会员中心', '支付中心'))
      OR m.permission IN ('gateway:log:list', 'gateway:quota:query', 'gateway:quota:grant', 'gateway:quota:revoke',
                          'gateway:token:list', 'gateway:redemption:list', 'gateway:redemption:query',
                          'member:user:query', 'pay:order:list') )
   AND NOT EXISTS (SELECT 1 FROM system_role_menus rm WHERE rm.role_id = (SELECT id FROM system_roles WHERE code = 'support') AND rm.menu_id = m.id);

-- 只读：业务三组的目录与页面（type<=2），不含任何按钮（type=3），不含系统管理与基础设施
INSERT INTO system_role_menus (role_id, menu_id, created_at)
SELECT (SELECT id FROM system_roles WHERE code = 'readonly'), m.id, (extract(epoch from now()) * 1000)::bigint
  FROM system_menus m
 WHERE m.status = 1 AND m.type IN (1, 2)
   AND ( m.name IN ('AI 网关', '会员中心', '支付中心')
      OR m.permission LIKE 'gateway:%' OR m.permission LIKE 'member:%' OR m.permission LIKE 'pay:%' )
   AND NOT EXISTS (SELECT 1 FROM system_role_menus rm WHERE rm.role_id = (SELECT id FROM system_roles WHERE code = 'readonly') AND rm.menu_id = m.id);
