-- V007 给计费归因补两列：这笔钱走的是哪个组、这个组是哪一层给的
--
-- ── 缺陷 ──
-- BillingGroupResolver 早就算出了来源：GroupSource 的 KDoc 第一句就写着「进日志：错账排查
-- 第一个要问的就是来源」。但它一次也没进过日志 —— RelayEngine 解析计费组那一步写的是
-- `is GroupResolution.Resolved -> r.group`，`r.source` 当场被扔掉。组 code 的遭遇更奇怪：它被
-- 一路传了六百行、穿过 execute / streamWithPreflight / bill / recordLog 四层函数签名，最后
-- recordLog 构造 UsageLog 时也没用它。bill 同样带着 group 参数而通篇未用。Kotlin 不对未使用的
-- 函数参数报警告（只报未使用的局部变量），所以这两处白传一次也没响过。
--
-- ── 后果 ──
-- gateway_settlements.pricing_snapshot 逐笔冻结了 ratio，所以「按什么倍率收了多少」查得出来；
-- 但**倍率相同的四层含义完全不同**：令牌覆盖是运营给单个 key 开的口子、用户例外是大客户谈判价、
-- 会员组是套餐、default 是标准价。快照不记是哪一层，于是「为什么是这个倍率」答不上来。
-- bill 里那条毛利倒挂告警正是活例：它打了 ratio 与 costDiscount，却没打组 —— 运营看到
-- 「ratio=3.0 margin inversion」时，无法判断这是谈判价配错了还是标准价本身配错了。
-- 撤销用户例外时这个缺口最疼：gateway_group_overrides 是硬删（无 deleted 列），删掉之后
-- 「这位客户曾经谈过什么价、remark 里的合同号」在该表里再无痕迹。本迁移补的是账务侧那一半
-- （这笔走的哪个组、那个组是哪一层给的）；合同号仍然只有给那张表加软删列才留得住，记在
-- backlog GROUP-OVERRIDE-ADMIN-CRUD-P1 后续项 4 —— 那是记录问题，不是计价问题。
--
-- ── 为什么加列，而不是把归因塞进 pricing_snapshot ──
-- snapshotPrice 对快照是**严格校验**的：JSON 非法、任一轨字段缺失、数值越界一律返回 null，
-- 然后 bill 把这笔转 MANUAL_REVIEW 并保留预留资金（V004 §7.1：账务不允许猜价）。往快照里加
-- 字段并让它参与校验，等于让升级瞬间所有**在途**旧快照解析失败 —— 那些请求的钱会全数卡在
-- 人工复核里。新列 nullable 则完全不碰 snapshotPrice。
-- 另一个理由：markUpstreamStarted（T2）会重写快照以并入渠道成本折扣，归因放在列上就不必跟着
-- 重写 —— 计费组在入口只解析一次，换渠道重试也不会变，reserve 时写一次即为终值。
--
-- ── 为什么两张表都要 ──
-- finalize 写 usage_log 时，**所有维度都取自 settlement 行**（含 worker 重放：那时可能是另一个
-- 进程、内存里什么都没有）。所以归因必须先固化在 gateway_settlements 上，才活得到 finalize，
-- 才能落进 gateway_usage_logs —— 而后者是管理台查账的主表。
--
-- ── 为什么 nullable 且不回填 ──
-- 旧行无从查证当时用的是哪个组：组配置可能已改名、已删除，倍率也可能已经改过。拿 'default'
-- 去填等于把「不知道」说成「走的是 default 组」—— 那正是本模块一贯拆掉的静默回落（V002 就是
-- 为此把路由与计价里的 `?: "default"` 和 `?: "1.0"` 两级兜底去掉的）。NULL 诚实表达
-- 「这条日志写在归因落库之前」，读的人不会把它误当成一次真实的组归属。
--
-- 其余取舍与 V005/V006 一致：只有 postgresql 方言（mysql 与 sqlite 那两棵树是从不运行的
-- 死重量，见 DEPLOY.md §多方言）；不加幂等守卫（MigrationEngine 已按 neton_schema_history
-- 跳过成功脚本，且整个脚本在 db.transaction 里，半失败整体回滚）。
-- 不建索引：这两列是查账时**读出来看**的，不是查询条件；gateway_usage_logs 是每请求一行的
-- 高频写入表，而它连 user_id 都没有索引（既有查询模式是管理台分页 + 时间倒序）。给高频写入表
-- 加一个没人用来过滤的索引只会拖慢写入。将来若要「按组统计收入」，那时再按真实查询形状加。
--
-- 宽度：group_code 与 gateway_groups.code 同为 VARCHAR(64)（V002 定的）；group_source 存
-- GroupSource 的枚举名，最长 TOKEN_OVERRIDE = 14 字符，VARCHAR(16) 留一点余量。

ALTER TABLE gateway_settlements ADD COLUMN group_code VARCHAR(64);
ALTER TABLE gateway_settlements ADD COLUMN group_source VARCHAR(16);

ALTER TABLE gateway_usage_logs ADD COLUMN group_code VARCHAR(64);
ALTER TABLE gateway_usage_logs ADD COLUMN group_source VARCHAR(16);
