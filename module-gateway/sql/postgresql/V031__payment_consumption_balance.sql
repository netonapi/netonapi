-- 一次性迁移已上线的微美元账本，保留余额、在途预留、流水编号和幂等凭据。
-- 部署时先停止旧版网关和结算 worker，迁移后由新版独占写入支付模块账本。
LOCK TABLE gateway_quota_accounts, gateway_quota_transactions IN ACCESS EXCLUSIVE MODE;

INSERT INTO pay_consumption_accounts (id, user_id, balance, reserved_balance, version, created_at, updated_at)
SELECT id, user_id, balance, reserved_balance, version, created_at, updated_at FROM gateway_quota_accounts;

INSERT INTO pay_consumption_transactions (id, user_id, type, amount, balance_after, ref, created_at)
SELECT id, user_id, type, amount, balance_after, ref, created_at FROM gateway_quota_transactions;

SELECT setval(pg_get_serial_sequence('pay_consumption_accounts', 'id'),
              COALESCE(MAX(id), 1), COUNT(*) > 0) FROM pay_consumption_accounts;
SELECT setval(pg_get_serial_sequence('pay_consumption_transactions', 'id'),
              COALESCE(MAX(id), 1), COUNT(*) > 0) FROM pay_consumption_transactions;

ALTER TABLE gateway_quota_accounts RENAME TO gateway_quota_accounts_legacy;
ALTER TABLE gateway_quota_transactions RENAME TO gateway_quota_transactions_legacy;

-- 发行版选择消费余额作为后台唯一钱包入口。原钱包数据与通用模块的能力继续保留。
UPDATE system_menus SET status = 0
 WHERE component IN ('pay/wallet/balance/index', 'pay/wallet/rechargePackage/index')
    OR permission LIKE 'pay:wallet:%'
    OR permission LIKE 'pay:wallet-recharge-package:%';

INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
SELECT '用户余额', 'pay:consumption-balance:query', 2, id, 'user-balance', 'pay/consumption-balance/index',
       'ant-design:wallet-outlined', 6, 1, (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint
  FROM system_menus WHERE type = 1 AND parent_id = 0 AND path = '/pay';

INSERT INTO system_menus (name, permission, type, parent_id, sort, status, created_at, updated_at)
SELECT actions.name, 'pay:consumption-balance:' || actions.permission, 3, page.id, actions.sort, 1,
       (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint
  FROM system_menus page
 CROSS JOIN (VALUES ('手动入账', 'credit', 1), ('赠送额度', 'gift', 2), ('撤回额度', 'revoke', 3))
             AS actions(name, permission, sort)
 WHERE page.component = 'pay/consumption-balance/index';

WITH prior_permissions AS (
    SELECT rm.role_id, m.permission
      FROM system_role_menus rm JOIN system_menus m ON m.id = rm.menu_id
     WHERE m.status = 1 AND m.permission IN ('gateway:quota:query', 'gateway:quota:grant', 'gateway:quota:revoke')
), grants AS (
    SELECT role_id, 'query' AS action FROM prior_permissions WHERE permission = 'gateway:quota:query'
    UNION SELECT id, 'query' FROM system_roles WHERE code = 'readonly'
    UNION SELECT role_id, 'credit' FROM prior_permissions WHERE permission = 'gateway:quota:grant'
    UNION SELECT role_id, 'gift' FROM prior_permissions WHERE permission = 'gateway:quota:grant'
    UNION SELECT role_id, 'revoke' FROM prior_permissions WHERE permission = 'gateway:quota:revoke'
), menus AS (
    SELECT grants.role_id, m.id AS menu_id
      FROM grants JOIN system_menus m ON m.permission = 'pay:consumption-balance:' || grants.action
    UNION
    SELECT grants.role_id, m.id FROM grants CROSS JOIN system_menus m
     WHERE grants.action = 'query' AND m.type = 1 AND m.parent_id = 0 AND m.path = '/pay'
)
INSERT INTO system_role_menus (role_id, menu_id, created_at)
SELECT role_id, menu_id, (extract(epoch from now()) * 1000)::bigint
  FROM menus WHERE NOT EXISTS (
      SELECT 1 FROM system_role_menus rm WHERE rm.role_id = menus.role_id AND rm.menu_id = menus.menu_id
  );
