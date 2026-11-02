-- V029：「用户定价」改名「协议定价」——这一页只做一件事：给单个用户谈专属价格（docs/gateway-pricing-spec.md §4.3.6）。
-- 「用户定价」容易被读成「普通用户看到的价格」，那其实是全站价（「模型定价」页）。
UPDATE system_menus SET name = '协议定价', updated_at = (extract(epoch from now()) * 1000)::bigint
 WHERE component = 'gateway/user-price/index' AND name = '用户定价';
