# 渠道真实成本与官方价同步 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 官方价与 new-api 同源并支持分档计价；每条渠道按模型有真实成本价（官方价 × 折扣 / 上游价目接口 / 手填），结算、体检、报表都用它；管理台按对外模型查看渠道与成本。

**Architecture:** 后端全部在 `neton-application-module-gateway`。分档价格以 `tiers` JSON 挂在价格行与渠道成本行上，纯函数 `PricingTiers` 负责解析与选档；定价快照的成本轨直接放「渠道实际成本价」并把折扣记为 `1`，结算公式 `PricingCalc.charge/cost` 不变。价源与上游价目解析都是纯函数，便于单测；拉取与写入在 Logic 层，经定时任务与管理接口触发。前端在 `neton-application-front-gateway`。

**Tech Stack:** Kotlin/Native（Neton 框架、neton-database DSL、kotlinx.serialization），PostgreSQL 迁移，Next.js + `@neton/application-front-kit`，harness（bash + python fakes）。

**Spec:** `netonapi-backend/docs/channel-cost-and-price-sync-design.md`（现行规则见同目录 `gateway-pricing-spec.md`）

## Global Constraints

- 只支持 PostgreSQL；迁移放 `neton-application-module-gateway/sql/postgresql/`，编号从 `V019` 起，已执行的迁移不得修改。
- 金额一律整数：价格解析为 μUSD / 1M（`PricingCalc.parseMicro`），倍率为万分位（`PricingCalc.parseRatio`），乘法用溢出检查；不得引入浮点进入结算路径（价源解析里的浮点只在换算成价格串时出现一次，按 μUSD 精度取整）。
- 路由排序不改（`RelayRouter.pick` / 重试逻辑原样）。
- 环境变量前缀 `NETONAPI_*`；删除 `NETONAPI_PRICE_SYNC_URL` / `NETONAPI_PRICE_SYNC_OVERWRITE`，设置了则启动报错并提示改用后台配置 `gateway.pricing.source_*`。
- 所有界面文案走 `useT("gateway")`，zh-CN / en-US 同一组键（`pnpm run check:locales` 通过）。
- 构建命令都带 `-Pnetonstream.local.openssl=true`；网关单测：`cd neton-application-module-gateway && ./gradlew macosArm64Test -Pnetonstream.local.openssl=true`；完整验收：`cd NewGate && NETONAPI_HARNESS_PORT=8801 ./harness/run.sh`。
- 提交信息用简单英文，不带 AI 署名；本计划完成前不推送。

## Review Focus

1. 总输入 token 恰好等于分档门槛 → 落在低档（`maxPromptTokens` 含等号）。测试放 Task 1。
2. 上游 `/api/pricing` 返回 `success:false`、HTML 或超时 → 保留旧成本、写 `cost_sync_error`，不清零。测试放 Task 6。
3. `model_mapping` 多个请求模型映射到同一个上游模型 → 每个请求模型都得到该成本行。测试放 Task 5。
4. `cost_source = upstream` 但没填 `upstream_pricing_format` → 保存渠道 400。测试放 Task 4。
5. 有 `tiers` 又有固定售价的模型 → 固定价只改基础档，其余档按加价率；预留按售价最贵档。测试放 Task 2。

---

## 第一步：价源与分档计价

### Task 1: 分档价格的数据与纯函数

**Files:**
- Create: `neton-application-module-gateway/sql/postgresql/V019__price_tiers_and_sync_runs.sql`
- Modify: `neton-application-module-gateway/src/commonMain/kotlin/model/ModelPrice.kt`（加 `tiers: String? = null`）
- Create: `neton-application-module-gateway/src/commonMain/kotlin/logic/PricingTiers.kt`
- Test: `neton-application-module-gateway/src/commonTest/kotlin/logic/PricingTiersTest.kt`

**Interfaces:**
- Produces:
  - `data class PriceTier(val maxPromptTokens: Long?, val input: String, val output: String, val cacheRead: String, val cacheWrite: String)`（`@Serializable`）
  - `object PricingTiers`：
    - `fun parse(json: String?): List<PriceTier>?`（空/空白 → null；非法 → `rejectField("tiers", GatewayErrorCodes.PRICE_TIERS_INVALID)`；校验：至少 2 档、`maxPromptTokens` 严格递增、仅最后一档为 null、各价格过 `PricingCalc.requirePrice`）
    - `fun render(tiers: List<PriceTier>): String`
    - `fun select(price: ModelPrice, totalInputTokens: Long): ModelPrice`（无 tiers 原样返回；否则返回四个价格字段替换为命中档、`tiers = null` 的副本）
    - `fun mostExpensive(price: ModelPrice): ModelPrice`（取 input+output 之和最大的那一档，用于预留）
    - `fun totalInput(usage: Usage): Long = promptTokens + cacheReadTokens + cacheWriteTokens`（与 `rawAmount` 的 freshInput 口径对齐：若 promptTokens 已含缓存读则不重复加 —— 用 `max(promptTokens, cacheReadTokens) + cacheWriteTokens`）
- V019 内容：`ALTER TABLE gateway_model_prices ADD COLUMN IF NOT EXISTS tiers TEXT;` 以及 `CREATE TABLE gateway_price_sync_runs (id BIGSERIAL PK, checked_at BIGINT NOT NULL, source VARCHAR(512), changed INT NOT NULL DEFAULT 0, new_models INT NOT NULL DEFAULT 0, unparsable INT NOT NULL DEFAULT 0, error TEXT)`（Task 4 使用）。
- 新错误码 `GatewayErrorCodes.PRICE_TIERS_INVALID`，前端两种语言补 `error.PRICE_TIERS_INVALID`。

- [ ] **Step 1: 写失败测试** `PricingTiersTest`：
  - `selectsLowTierAtExactThreshold`：tiers `[{200000,"1.25","10","0.125","0"},{null,"2.5","15","0.25","0"}]`，`select(price, 200000).inputPrice == "1.25"`，`select(price, 200001).inputPrice == "2.5"`。
  - `noTiersReturnsSamePrice`。
  - `rejectsNonIncreasingOrOpenMiddleTier`：`[{100,…},{100,…}]` 与 `[{null,…},{100,…}]` 都 `assertFails`。
  - `mostExpensivePicksHighestTier`：上例返回 `inputPrice == "2.5"`。
  - `totalInputCountsCacheOnce`：`Usage(promptTokens=1000, cacheReadTokens=800, cacheWriteTokens=50)` → `1050`。
- [ ] **Step 2: 运行确认失败**（未定义符号）。
- [ ] **Step 3: 实现** `PricingTiers`、`PriceTier`、迁移、`ModelPrice.tiers`、错误码与两份语言包。
- [ ] **Step 4: 运行网关单测全部通过。**
- [ ] **Step 5: Commit** `Add tiered model prices`

### Task 2: 分档进入售价、预留与结算

**Files:**
- Modify: `logic/SalePricing.kt`（`resolve` 对 tiers 逐档 × 加价率；固定售价只改基础字段）
- Modify: `logic/RelayEngine.kt`（预留用 `PricingTiers.mostExpensive(salePrice)`；`pricingSnapshot` 的两条价格轨各带 `"tiers"`（无则 `null`）；`snapshotPrice` 解析 `tiers` 可缺省；结算前对 sale / cost 轨各调 `PricingTiers.select(track, PricingTiers.totalInput(usage))`；快照另记 `"tier"` 命中档序号写入用量日志的现有 `pricing_snapshot`）
- Modify: `controller/admin/price/PriceController.kt`（`SavePriceRequest.tiers: String? = null`，`toModelPrice` 经 `PricingTiers.parse` 规范化；update 时不带 `tiers` 字段 = 保留，传空串 = 清除，与 `saleOverride` 同语义）
- Modify: `logic/PricingCheckLogic.kt`（逐档比较，取最差档；`PricingCombo` 增加 `tier: Int`）
- Test: `src/commonTest/kotlin/logic/SalePricingTest.kt`（追加）、harness 新场景 S91

**Interfaces:**
- Consumes: Task 1 `PricingTiers.*`、`ModelPrice.tiers`
- Produces: `SalePricing.resolve(official, markup)` 返回值的 `tiers` 为加价后的分档；`PricingCombo.tier`

- [ ] **Step 1: 写失败测试** `SalePricingTest.markupAppliesToEveryTier`（加价率 2，上例分档 → 第二档 input `"5"`）与 `fixedSalePriceOnlyChangesBaseTier`（`saleOverride {"input":"9"}` → 基础字段 input `"9"`，tiers 第一档 input `"2.5"`（=1.25×2）不受影响）。
- [ ] **Step 2: 运行确认失败。**
- [ ] **Step 3: 实现上列改动。**
- [ ] **Step 4: harness S91**（插在 `# ══ S87` 之前）：价格 `m-tier` 基础 `1/2`，tiers `[{1000,"1","2","0","0"},{null,"4","8","0","0"}]`；假上游 9996 返回 usage —— 需在 `fakes.py` `ok` 模式支持请求头 `x-fake-prompt-tokens` 覆盖 usage.prompt_tokens；发两次请求：prompt 500 与 prompt 5000，断言 `charged` 分别等于按低档、高档计算的值（输出 token 用 fake 默认值），且快照 `"tier":0` / `"tier":1`。
- [ ] **Step 5: 单测 + harness 通过。**
- [ ] **Step 6: Commit** `Bill tiered prices by total input tokens`

### Task 3: basellm 与 models.dev 价源

**Files:**
- Modify: `logic/PriceCalibrationLogic.kt`（`parse` 增加 `"basellm"`、`"models_dev"`；`SourcePrice` 增加 `tiers: List<PriceTier>? = null`；`merge` 写入 tiers；`CalibrationRow.status` 增加 `UNPARSABLE`，附 `note`）
- Create: `logic/BillingExprParser.kt`
- Modify: `setting/GatewaySettingKeys.kt`（`PRICING_SOURCE_FORMAT` 默认 `basellm`，`PRICING_SOURCE_URL` 默认 `https://basellm.github.io/llm-metadata/api/newapi/ratio_config-v1-base.json`；描述列出五种格式）
- Test: `src/commonTest/kotlin/logic/BillingExprParserTest.kt`、`PriceCalibrationParseTest.kt`（追加）
- Modify: `NewGate/harness/fakes.py`（GET `/basellm.json`、`/models-dev.json`），harness S89 改用 basellm 格式并新增 models.dev 断言

**Interfaces:**
- Produces:
  - `object BillingExprParser { fun parse(expr: String): Result<ParsedPrice> }`，`data class ParsedPrice(val base: PriceTier, val tiers: List<PriceTier>?)`；只认 `tier("<name>", <terms>)` 与 `len <= N ? <expr> : <expr>`（右侧可再嵌套），`<terms>` 为 `var * number` 以 `+` 相连，变量 `p`/`c`/`cr`/`cc`/`cc1h`；`cc1h` 忽略并在 `Result` 的附注里返回 `"cc1h ignored"`；其它任何 token → `Result.failure`。
  - `PriceCalibrationLogic.parseBasellm(root: JsonElement): List<SourcePrice>`、`parseModelsDev(root: JsonElement): List<SourcePrice>`（companion，纯函数）。models.dev 结构 `{provider:{models:{id:{cost:{input,output,cache_read,cache_write}}}}}`，同名模型优先取 provider 为 `openai`/`anthropic`/`google`/`deepseek` 的那条，其余按 provider 名字典序取第一条。

- [ ] **Step 1: 写失败测试**：
  - `singleTier`：`tier("standard", p * 2.5 + cr * 1.25 + c * 10)` → base input `"2.5"`、output `"10"`、cacheRead `"1.25"`、tiers null。
  - `twoTiersByLength`：`len <= 200000 ? tier("0_200k", p * 1.25 + cr * 0.125 + c * 10) : tier("200k_plus", p * 2.5 + cr * 0.25 + c * 15)` → tiers 两档，门槛 200000，第二档 output `"15"`。
  - `cc1hIgnoredWithNote`、`unknownShapeFails`（`p * 2 ^ 2`、`max(p,1)`）。
  - `parseBasellm` 对 `{"success":true,"data":{"billing_mode":{...},"billing_expr":{"a":"tier(...)","bad":"foo"}}}` 返回 `a`，`bad` 进不可解析清单。
  - `parseModelsDev` 同名模型取 `anthropic` 那条。
- [ ] **Step 2: 运行确认失败。**
- [ ] **Step 3: 实现。**
- [ ] **Step 4: 单测通过；harness S89 改为 basellm 并补 models.dev 断言后通过。**
- [ ] **Step 5: Commit** `Calibrate official prices from basellm and models.dev`

### Task 4: 定时同步只提示不写库

**Files:**
- Modify: `job/GatewayJobs.kt`（`gateway-price-sync` 改为调用 `PriceCalibrationLogic.check()`）
- Modify: `logic/PriceCalibrationLogic.kt`（`suspend fun check(): CalibrationPreview` = `preview()` 并写一行 `gateway_price_sync_runs`）
- Create: `model/PriceSyncRun.kt`、`table/PriceSyncRunTable.kt`
- Modify: `logic/PriceImportLogic.kt`（删除 `syncFromEnv`、`ENV_SYNC_URL`、`ENV_SYNC_OVERWRITE`）；`controller/admin/price/PriceController.kt`（`POST /sync` 改为 `check()`，返回预览）
- Modify: `init/GatewayRuntimeBootstrap.kt`（设置了 `NETONAPI_PRICE_SYNC_URL` 或 `NETONAPI_PRICE_SYNC_OVERWRITE` → `error("... removed; configure gateway.pricing.source_format / source_url in Settings")`）
- Modify: `logic/OverviewLogic.kt`（`OverviewVO.priceSync: PriceSyncRunVO?` 最近一次）、`controller/gateway/MetricsController.kt`（`netonapi_price_pending_changes` gauge = 最近一次 `changed + new_models`）
- Modify: `NewGate/deploy/monitor/netonapi-watch.sh`（新增检查项 `prices`：`netonapi_price_pending_changes > 0` → 「官方价有 N 处变化待确认」）
- Modify: harness（删除用 `NETONAPI_PRICE_SYNC_URL` 的旧场景断言，改测「定时检查只写 sync_runs、价格表不变」）、`NewGate/DEPLOY.md`、`docker-compose.yml`、`.env.example`（去掉 `PRICE_SYNC_URL`）
- Modify: 渠道保存校验见 Task 5（Review Focus 4 在那里）

- [ ] **Step 1: 写 harness 断言**：调用 `POST /admin/gateway/price/sync` 后 `gateway_price_sync_runs` 多一行且 `changed` 正确，`gateway_model_prices` 无变化；`/metrics` 含 `netonapi_price_pending_changes`。
- [ ] **Step 2: 实现。**
- [ ] **Step 3: harness 通过；启动时带 `NETONAPI_PRICE_SYNC_URL=x` 退出非 0（加到 S0 旁边，同 S0 写法）。**
- [ ] **Step 4: Commit** `Make the scheduled price sync report changes instead of writing them`

## 第二步：渠道真实成本

### Task 5: 渠道成本数据与解析顺序

**Files:**
- Create: `sql/postgresql/V020__channel_costs.sql`
- Modify: `model/Channel.kt`（`costSource: String = "official"`, `upstreamPricingFormat: String? = null`, `upstreamPricingUrl: String? = null`, `upstreamGroupRatio: String = "1"`, `upstreamCreditRate: String = "1"`, `costSyncedAt: Long? = null`, `costSyncError: String? = null`）
- Create: `model/ChannelModelCost.kt`、`table/ChannelModelCostTable.kt`
- Modify: `model/PriceHistory.kt`（加 `channelId: Long? = null`, `status: String = "applied"`）
- Create: `logic/ChannelCostLogic.kt`
- Modify: `logic/RelayEngine.kt`（`costBasisFor` 改为调用 `ChannelCostLogic.costFor`，命中渠道成本行时快照 `costDiscount = "1"`）、`logic/PricingCheckLogic.kt`、渠道保存控制器（校验 `costSource ∈ {official, upstream, manual}`；`upstream` 时 `upstreamPricingFormat ∈ {newapi, openrouter}` 必填 → `CHANNEL_PRICING_FORMAT_REQUIRED`；两个倍率过 `PricingCalc.requireRatio`）
- Test: `src/commonTest/kotlin/logic/ChannelCostLogicTest.kt`

**Interfaces:**
- V020：`gateway_channels` 加上列；`CREATE TABLE gateway_channel_model_costs (channel_id BIGINT NOT NULL, model VARCHAR(128) NOT NULL, input_price VARCHAR(32) NOT NULL, output_price VARCHAR(32) NOT NULL, cache_read_price VARCHAR(32) NOT NULL DEFAULT '0', cache_write_price VARCHAR(32) NOT NULL DEFAULT '0', per_request_price BIGINT, tiers TEXT, source VARCHAR(16) NOT NULL, stale SMALLINT NOT NULL DEFAULT 0, updated_at BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (channel_id, model))`；`gateway_price_history` 加 `channel_id BIGINT`、`status VARCHAR(16) NOT NULL DEFAULT 'applied'`。
- Produces:
  - `data class CostBasis(val price: ModelPrice, val discount: String, val source: String)`（`source` ∈ `channel` / `official`）
  - `object ChannelCostLogic { suspend fun costFor(channel: Channel, requestModel: String, upstreamModel: String, requestPrice: ModelPrice?): CostBasis?; fun costForLoaded(channel, requestModel, upstreamModel, requestPrice, costs: Map<Pair<Long,String>, ChannelModelCost>, prices: Map<String, ModelPrice>): CostBasis? }`（后者纯函数供体检批量用）。规则：有 `(channel.id, requestModel)` 成本行 → `CostBasis(行转 ModelPrice, "1", "channel")`；否则官方：`prices[upstreamModel] ?: requestPrice` × `channel.costDiscount`。
  - `fun ChannelModelCost.toModelPrice(model: String): ModelPrice`

- [ ] **Step 1: 写失败测试**：`channelRowWinsAndIgnoresDiscount`、`fallsBackToMappedOfficialTimesDiscount`、`mappedTargetWithoutPriceUsesRequestPrice`、`manyRequestModelsToOneUpstream`（Review Focus 3：两个请求模型各自有成本行时各取各的；都没有时都回到映射目标官方价）。
- [ ] **Step 2: 运行确认失败。**
- [ ] **Step 3: 实现；体检 `PricingCombo` 增加 `costSource`，成本来自渠道行时 `costDiscount` 显示 `"1"`。**
- [ ] **Step 4: 单测通过；harness 新增 S92 前半：插入渠道成本行后请求的 `cost` 等于渠道行价（不乘折扣 2.0）；删除后回到官方价 × 2.0；保存 `costSource=upstream` 不带格式 → 400 `CHANNEL_PRICING_FORMAT_REQUIRED`。**
- [ ] **Step 5: Commit** `Resolve channel costs from per-model cost rows`

### Task 6: 上游价目解析（纯函数）

**Files:**
- Create: `logic/UpstreamPricingParser.kt`
- Test: `src/commonTest/kotlin/logic/UpstreamPricingParserTest.kt`

**Interfaces:**
- Produces:
  - `data class UpstreamPrice(val upstreamModel: String, val input: String, val output: String, val cacheRead: String, val cacheWrite: String, val perRequest: Long?)`（USD / 1M 价格串；按次为 μUSD）
  - `object UpstreamPricingParser { fun parse(format: String, body: String): Result<List<UpstreamPrice>>; fun scale(p: UpstreamPrice, groupRatio: String, creditRate: String): UpstreamPrice; fun withOfficialTiers(base: UpstreamPrice, official: ModelPrice?): List<PriceTier>? }`
- 换算（new-api）：`quota_type == 0`：input = `model_ratio × 2`，output = input × `completion_ratio`，cacheRead = input × `cache_ratio`（缺省 → `"0"`），cacheWrite = input × `create_cache_ratio`（缺省 → `"0"`）；`quota_type == 1`：perRequest = `model_price × 1_000_000`（μUSD）。也接受 `/api/ratio_config` 形状（`data.model_ratio` / `completion_ratio` / `cache_ratio` / `model_price` 映射表）。`success != true` → failure（消息带上游 `message`）。
- 换算（openrouter）：`data[].id` 原样作为 upstreamModel（不去前缀，渠道映射负责对应），每 token 价 ×10⁶；`-1` 跳过。
- `withOfficialTiers`：官方价有 tiers 时，各档 = 上游基础价 × (官方第 n 档 ÷ 官方基础档)，逐项；官方某项为 0 时该档该项取上游基础价。

- [ ] **Step 1: 写失败测试**：`newApiRatioToPrice`（`model_ratio 1.25, completion_ratio 8, cache_ratio 0.1` → input `"2.5"`、output `"20"`、cacheRead `"0.25"`）、`newApiPerRequest`（`quota_type 1, model_price 0.04` → perRequest `40000`）、`ratioConfigShape`、`successFalseFails`（Review Focus 2）、`htmlBodyFails`、`openRouterPerToken`、`scaleMultipliesGroupAndCredit`（group `0.8` × credit `0.14` × input `"2.5"` → `"0.28"`）、`officialTiersScaleProportionally`（官方基础 1.25 / 档二 2.5，上游基础 1 → 档二 2）。
- [ ] **Step 2: 运行确认失败。**
- [ ] **Step 3: 实现。**
- [ ] **Step 4: 单测通过。**
- [ ] **Step 5: Commit** `Parse new-api and OpenRouter upstream pricing`

### Task 7: 渠道成本同步、挂起与管理接口

**Files:**
- Create: `logic/ChannelCostSyncLogic.kt`
- Create: `controller/admin/channel/ChannelCostController.kt`
- Modify: `job/GatewayJobs.kt`（`gateway-channel-cost-sync`，cron `0 */6 * * *`）
- Modify: `NewGate/harness/fakes.py`（`newapi_pricing` 模式：`GET /api/pricing` 返回由环境变量 `PRICING_JSON` 指定的文件内容，便于场景改价）
- Test: `src/commonTest/kotlin/logic/ChannelCostSyncLogicTest.kt`（纯判定部分）+ harness S92 后半

**Interfaces:**
- Consumes: Task 5 表与 `ChannelCostLogic`，Task 6 `UpstreamPricingParser`
- Produces:
  - `ChannelCostSyncLogic.syncChannel(channelId: Long, operatorId: Long?): ChannelCostSyncResult`、`syncAll(): List<ChannelCostSyncResult>`；`data class ChannelCostSyncResult(val channelId: Long, val updated: Int, val pending: Int, val stale: Int, val error: String?)`
  - 纯判定 `fun decide(old: ChannelModelCost?, new: ModelPrice): Decision`，`enum Decision { APPLY, PENDING }`：任一项 `old>0` 且 `|new-old|/old > 0.5`，或 new 任一非零项变 0 / 全为 0 → PENDING；`old == null` 时仅「全为 0」PENDING。
  - 上游模型名 → 请求模型：`channel.modelMapping` 反查（值 == upstreamModel 的所有键）+ 渠道 `models` 里同名者。
  - 上游列表里没有、但本渠道已有 `source=upstream` 成本行的模型 → `stale = 1`（不删除）。
  - 拉取失败 → 不动成本行，`cost_sync_error` = 失败原因，`cost_synced_at` 不更新。
  - 写入 / 挂起都记 `gateway_price_history`（`action="channel_cost"`, `channel_id`, `status` = `applied` / `pending`）。
  - 接口（权限 `gateway:channel:update`，列表 `gateway:channel:query`）：`GET /admin/gateway/channel-cost/list?channelId=`（含 pending）、`POST /channel-cost/sync?channelId=`、`PUT /channel-cost/manual`（body：channelId, model, 价格字段, tiers）、`DELETE /channel-cost/delete?channelId=&model=`、`POST /channel-cost/pending/{historyId}/accept`、`POST /channel-cost/pending/{historyId}/ignore`。

- [ ] **Step 1: 写失败单测** `decide`：涨 60% → PENDING；跌 60% → PENDING；涨 40% → APPLY；变 0 → PENDING；首次非零 → APPLY；首次全 0 → PENDING。
- [ ] **Step 2: 运行确认失败。**
- [ ] **Step 3: 实现。**
- [ ] **Step 4: harness S92 后半**：假 new-api（9997，`newapi_pricing`）报 `up-a` ratio 1；渠道 `costSource=upstream`、`newapi`、映射 `{"m-a":"up-a"}`、groupRatio `0.5`、creditRate `0.2` → 同步后 `m-a` 成本 input `"0.2"`（=2×0.5×0.2）；改价到 ratio 2（+100%）→ pending 一条、成本仍 `"0.2"`、accept 后变 `"0.4"`；上游移除 `up-a` → `stale=1`；上游返回 `{"success":false}` → `cost_sync_error` 非空、成本不变。
- [ ] **Step 5: 单测 + harness 通过。**
- [ ] **Step 6: Commit** `Sync channel costs from upstream pricing with review for large changes`

## 第三步：管理界面

### Task 8: 渠道成本界面

**Files:**
- Modify: `neton-application-front-gateway/src/pages/channel-list.tsx`（表单加成本来源 / 上游价目格式 / 地址 / 上游分组倍率 / 上游额度单价；列加「成本来源」与同步状态；行操作「成本」）
- Create: `neton-application-front-gateway/src/pages/channel-cost-drawer.tsx`（列出每个对外模型的实际成本价与来源、`stale` 标记；手填 / 删除；「立即同步」；「待确认变动」列表带接受 / 忽略，显示旧值 → 新值）
- Modify: `src/locales/{zh-CN,en-US}.ts`

- [ ] **Step 1: 实现**；`costSource !== "upstream"` 时隐藏上游字段；`official` 时折扣字段说明「成本 = 官方价 × 折扣」，`upstream` 时说明「成本 = 上游价 × 上游分组倍率 × 上游额度单价」。
- [ ] **Step 2: `pnpm install`（NewGate/netonapi-frontend）后 `npx tsc --noEmit -p apps/shell` 与 `pnpm run check:locales` 通过。**
- [ ] **Step 3: Commit** `Manage channel costs in the admin console`

### Task 9: 对外模型视图与体检提示

**Files:**
- Create: `sql/postgresql/V021__model_display_name.sql`（`gateway_model_prices.display_name VARCHAR(64)`）
- Modify: `model/ModelPrice.kt`（`displayName: String? = null`）、`controller/admin/price/PriceController.kt`（保存 displayName，保留语义同 tiers）、`controller/app/gateway/ModelCatalogController.kt`（`PublicModelVO.displayName`）
- Modify: `logic/PricingCheckLogic.kt`（`PricingCheckRow` 增加 `channels: List<ChannelRouteVO>`：`channelId, name, priority, weight, status, cooldownUntil, lastProbeOk, costInput, costOutput, costSource, stale`；`hints: List<String>`：`PRIORITY_COST_INVERSION`（存在更高优先级渠道比某个健康的更低优先级渠道贵，按基础档 input+output 比较）、`UPSTREAM_NOT_PRICED`、`COST_STALE`、`OFFICIAL_ONLY`（所有承载渠道都是 `official` 来源且折扣为 1））
- Modify: `neton-application-front-gateway/src/pages/price-list.tsx`（行可展开显示 channels 表与 hints；展示名可编辑）
- Modify: `neton-application-client-gateway/src/pages/model-catalog.tsx` 与 `NewGate/netonapi-client/apps/console/src/components/pricing-view.tsx`（有 displayName 时显示 displayName，模型 ID 作为副标题）
- Modify: 两边 locales

- [ ] **Step 1: 写单测** `PricingCheckLogicTest`（新建，纯函数部分抽成 `fun hintsFor(rows: List<ChannelRouteVO>): List<String>`）：高优先级贵 → `PRIORITY_COST_INVERSION`；低优先级渠道冷却中 → 不提示；全是 official 折扣 1 → `OFFICIAL_ONLY`。
- [ ] **Step 2: 运行确认失败 → 实现 → 通过。**
- [ ] **Step 3: harness S92 末尾**：两条渠道（优先级 10 成本高、优先级 5 成本低）→ `/price/check` 该模型 hints 含 `PRIORITY_COST_INVERSION`。
- [ ] **Step 4: 前端 tsc 与 locales 检查通过（admin 与 console 两处）。**
- [ ] **Step 5: Commit**（各仓分别）`Show channels and cost hints per public model`

### Task 10: 文档与收尾

**Files:**
- Modify: `netonapi-backend/docs/gateway-pricing-spec.md`（新增：分档计价、渠道成本来源与解析顺序、上游价目换算、挂起规则、定时同步只提示、对外模型展示名、体检新提示；更新接口与权限表、验收表）
- Modify: `netonapi-backend/docs/channel-cost-and-price-sync-design.md`（状态改为「已实现」）
- Modify: `neton-application-module-gateway/SPEC.md`「定价管理」节、`NewGate/DEPLOY.md`「定价与毛利」节与上线前检查

- [ ] **Step 1: 更新文档。**
- [ ] **Step 2: 完整 harness 全部通过；网关单测全部通过。**
- [ ] **Step 3: Commit** `Document channel costs and price sync`；然后按用户要求推送。
