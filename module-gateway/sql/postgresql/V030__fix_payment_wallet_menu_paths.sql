-- V012/V015 把两页提到 /pay 下。保留路径中的 wallet 层级以匹配前端注册的路由。
UPDATE system_menus AS menu
   SET path = 'wallet/' || menu.path,
       updated_at = (extract(epoch from now()) * 1000)::bigint
  FROM system_menus AS parent
 WHERE menu.parent_id = parent.id
   AND parent.type = 1
   AND parent.parent_id = 0
   AND parent.path = '/pay'
   AND menu.type = 2
   AND (menu.component, menu.path) IN (
       ('pay/wallet/balance/index', 'balance'),
       ('pay/wallet/rechargePackage/index', 'recharge-package')
   );
