-- V002 分组计费身份：code 即身份、name 仅显示（SQLite 方言）
-- 设计与动因见 postgresql/V002__group_billing_identity.sql，此处只记方言差异。
-- 注意：NanoGate 只构建 postgres|mysql 两种驱动（见本模块 build.gradle.kts 的
-- ngMigrationDialect），这份脚本不会被嵌入 NanoGate 的二进制；保留它是为了不
-- 让本模块的三方言迁移集分叉，其它下游 fork 若链接 sqlite 驱动才用得上。

-- SQLite 的 ADD COLUMN 不支持「先可空再收紧 NOT NULL」（没有 ALTER COLUMN），
-- 带 NOT NULL 的新列必须给默认值。因此这里以 DEFAULT '' 落列后立即回填。
-- 残留的 DEFAULT '' 是已知差异：省略 code 的插入会得到空串，而部分唯一索引
-- 只允许一行空串，所以最多产生一条垃圾数据；应用层始终显式写 code。
ALTER TABLE gateway_groups ADD COLUMN code VARCHAR(64) NOT NULL DEFAULT '';
UPDATE gateway_groups SET code = name WHERE code = '';

ALTER TABLE gateway_groups ADD COLUMN member_group_id BIGINT;

-- SQLite 支持部分索引，与 PostgreSQL 同构。
CREATE UNIQUE INDEX uk_gateway_groups_code
    ON gateway_groups(code) WHERE deleted = 0;

CREATE UNIQUE INDEX uk_gateway_groups_member_group
    ON gateway_groups(member_group_id) WHERE deleted = 0 AND member_group_id IS NOT NULL;

CREATE TABLE gateway_group_overrides (
    user_id BIGINT PRIMARY KEY,
    group_code VARCHAR(64) NOT NULL,
    remark VARCHAR(256),
    created_at BIGINT,
    updated_at BIGINT
);
