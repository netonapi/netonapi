-- ═══════════════════════════════════════════════════════════════════════
-- V002 分组计费身份：code 即身份、name 仅显示
-- ═══════════════════════════════════════════════════════════════════════
-- 动因：分组名字同时被三处当身份用 ——
--   RelayRouter.candidates          按 channel.groups 里的名字筛渠道
--   RelayEngine                     按名字查 ratio，查不到回落 '1.0'
--   gateway_tokens.group_override   存的也是名字
-- 而 gateway_groups 上 name 没有唯一约束，GroupController 又允许自由改名与删除。
-- 后果分两种，都不好：路由失配会报错（调不通，吵但能发现），倍率回落不报错
-- （照原价扣费，静默少收）。预付费模式里后者是最难发现的一类故障。
--
-- 本脚本只做加法：新增稳定标识 code 并回填、加唯一约束、加会员组映射列、
-- 加用户级例外表。不改既有列语义，不动其它模块的迁移脚本。
--
-- ⚠️ 不要回头改 V001：迁移 checksum 在编译期算好并与库中记录比对，改已应用的
--    脚本会让 MigrationStartupPrecheck 判 mismatch 并拒绝启动。这是设计如此。

-- 1) 稳定标识 code。先加可空列 → 回填 → 收紧 NOT NULL，
--    这样对已有行不需要默认值，也不会留下「code 是空串」却被当成合法值的中间态。
ALTER TABLE gateway_groups ADD COLUMN code VARCHAR(64);
UPDATE gateway_groups SET code = name WHERE code IS NULL;
ALTER TABLE gateway_groups ALTER COLUMN code SET NOT NULL;

-- 2) 会员组映射：一个会员组最多映射到一个计费组。
--    归属关系在 module-member（member_users.group_id / member_groups），倍率与映射
--    配置在 gateway —— member_groups 只有 name/remark/status，没有任何金钱语义，
--    把 ratio 塞进去会污染所有使用 member 的产品。
ALTER TABLE gateway_groups ADD COLUMN member_group_id BIGINT;

-- 与 uk_gateway_model_prices_model 同构：部分索引把软删行排除在唯一性之外。
-- gateway_groups 带 @SoftDelete，若用无条件唯一索引，软删一个组之后它的 code 与
-- 映射就永久占位、再也无法复用同名重建。
CREATE UNIQUE INDEX uk_gateway_groups_code
    ON gateway_groups(code) WHERE deleted = 0;

CREATE UNIQUE INDEX uk_gateway_groups_member_group
    ON gateway_groups(member_group_id) WHERE deleted = 0 AND member_group_id IS NOT NULL;

-- 3) 用户级例外（大客户谈判价）。放 gateway 而不是 member：这纯粹是计费语义，
--    且自助用户绝不能自己改它 —— 越权提组等于自己给自己打折。
--    解析优先级由装配层的 GroupResolver 决定，本表只回答「这个用户例外走哪个组」。
CREATE TABLE gateway_group_overrides (
    user_id BIGINT PRIMARY KEY,
    group_code VARCHAR(64) NOT NULL,
    remark VARCHAR(256),
    created_at BIGINT,
    updated_at BIGINT
);

-- 4) 既有引用无需重写：回填后 code 与 name 逐值相同，channel.groups 的 CSV 与
--    token.group_override 里的字符串仍然命中同一批组，路由与计价行为不变。
--    此后改 name 只改显示、改不动身份；真正「按 code 匹配」由代码侧完成。
