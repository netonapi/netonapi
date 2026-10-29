-- V015：补救 V012 里那些「按权限串匹配、而权限串随后被改名」的语句
--
-- ── 出了什么事 ──
-- V012 写的时候，payment 的读权限还叫 pay:*:list / :page / :menu。本轮统一（payment V002）把它们
-- 全改成了 :query，而 payment 排在 gateway 之前 —— 于是 V012 跑到时，它按旧串写的 WHERE 一条都匹配不上：
--   · 「转账订单 / 提现订单 / 冻结管理」没被停用，仍留在侧栏；
--   · 「钱包余额 / 充值套餐」没被挪到支付中心，而它们的父目录「钱包管理」是按**名字**匹配的、
--      停用成功了 —— 于是这两页被埋在一个停用目录下，比改之前更不可达；
--   · 财务与客服角色按旧串授的支付菜单全部落空（财务丢了支付订单/退款/钱包/套餐，客服丢了支付订单）。
-- UPDATE 匹配 0 行不报错，迁移显示成功，这就是 ENGINEERING_RULES 里那条「静默 no-op」最真实的样子。
--
-- ── 这次怎么写才不会再犯 ──
-- 结构性改动（停用、挪父级）一律按 **component** 匹配：它是页面文件的 key，不随权限词汇表变化。
-- 只有授权语句必须用权限串 —— 那就用统一后的终态串，并且本迁移排在所有改名之后。

-- 1) 停用与本产品无关的三页（component 稳定，不受改名影响）
UPDATE system_menus SET status = 0, updated_at = (extract(epoch from now()) * 1000)::bigint
 WHERE component IN ('pay/transfer/index', 'pay/withdraw/index', 'pay/wallet/freeze/index');

-- 2) 把钱包余额与充值套餐从已停用的「钱包管理」目录提到支付中心下
UPDATE system_menus
   SET parent_id = (SELECT id FROM system_menus WHERE name = '支付中心' AND type = 1 AND parent_id = 0),
       sort = CASE component WHEN 'pay/wallet/balance/index' THEN 6 ELSE 7 END,
       updated_at = (extract(epoch from now()) * 1000)::bigint
 WHERE component IN ('pay/wallet/balance/index', 'pay/wallet/rechargePackage/index');

-- 3) 财务：补回按旧串授失败的支付页面与套餐写按钮
INSERT INTO system_role_menus (role_id, menu_id, created_at)
SELECT (SELECT id FROM system_roles WHERE code = 'finance'), m.id, (extract(epoch from now()) * 1000)::bigint
  FROM system_menus m
 WHERE m.status = 1
   AND m.permission IN ('pay:order:query', 'pay:refund:query', 'pay:wallet:query',
                        'pay:wallet-recharge-package:query', 'pay:wallet-recharge-package:create',
                        'pay:wallet-recharge-package:update', 'pay:wallet-recharge-package:delete')
   AND EXISTS (SELECT 1 FROM system_roles WHERE code = 'finance')
   AND NOT EXISTS (SELECT 1 FROM system_role_menus rm
                    WHERE rm.role_id = (SELECT id FROM system_roles WHERE code = 'finance') AND rm.menu_id = m.id);

-- 4) 客服：补回支付订单（只读）
INSERT INTO system_role_menus (role_id, menu_id, created_at)
SELECT (SELECT id FROM system_roles WHERE code = 'support'), m.id, (extract(epoch from now()) * 1000)::bigint
  FROM system_menus m
 WHERE m.status = 1 AND m.permission = 'pay:order:query'
   AND EXISTS (SELECT 1 FROM system_roles WHERE code = 'support')
   AND NOT EXISTS (SELECT 1 FROM system_role_menus rm
                    WHERE rm.role_id = (SELECT id FROM system_roles WHERE code = 'support') AND rm.menu_id = m.id);

-- 5) 清掉指向已停用菜单的授权行：留着不会造成越权（运行期按 status 过滤），
--    但会让「角色管理」页面上勾着一堆看不见的条目，运营无从判断自己到底授了什么。
DELETE FROM system_role_menus rm
 USING system_menus m
 WHERE rm.menu_id = m.id AND m.status = 0
   AND rm.role_id IN (SELECT id FROM system_roles WHERE code IN ('gateway_ops', 'finance', 'support', 'readonly'));
