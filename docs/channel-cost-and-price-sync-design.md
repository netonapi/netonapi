# 渠道真实成本与官方价同步 · 设计

> 日期：2026-10-21
> 状态：已实现（2026-10-22），规则以 [gateway-pricing-spec.md](./gateway-pricing-spec.md) §3.1、§7、§12 为准
> 关联：[gateway-pricing-spec.md](./gateway-pricing-spec.md)（现行计价规则）

## 1. 意图

NetonAPI 对用户只露少数几个模型（如「Opus 5.5」「Gemini 4.0」），背后接很多家平台：便宜的中转站优先用，不可用就切到别家，最后由 ChatGPT / Gemini / Claude 官方 API 兜底。

要做到「用了多少量就知道最终花了多少钱」，有两件事现在做不到：

1. **官方价要跟着权威来源走**。new-api 生态用 GitHub 上 basellm/llm-metadata 维护的价目（364 个模型，含按上下文长度分档的价格）。我们现在默认用 LiteLLM，同步任务与校准是两套，也存不下分档价。
2. **每条渠道的成本是真实的**。现在一条渠道只有一个折扣，成本 = 官方价 × 折扣。而中转站上游往往每个模型价格不同、还有它们自己的分组倍率；这些中转站多半就是 new-api / one-api，公开 `/api/pricing`，可以直接拉到。

## 2. 目标与非目标

**目标**

- 官方价默认与 new-api 同源（basellm/llm-metadata），并支持 models.dev、LiteLLM、OpenRouter；支持按上下文长度分档计价。
- 每条渠道、每个模型有一个**实际成本价**，来源三选一：官方价 × 折扣 / 上游价目接口 / 手填。
- 价格变动（官方价或渠道成本）定时发现、提醒、经确认后写入，并留记录。
- 亏本检查、利润报表、每笔请求的成本都用实际成本价。
- 管理界面能按「对外模型」看到承载它的所有渠道、各自成本与优先级。

**非目标**

- **不改路由排序**。路由仍是「优先级分层 → 同层按权重 → 不可用排除 → 首字节前失败换下一家」（`RelayRouter.pick` + `RelayEngine` 重试）。官方兜底 = 官方渠道设最低优先级。成本只用于展示和提示，不自动改变顺序（用户决定：优先级更科学）。
- 不做上游账单自动对账（后续单独设计）。
- 不做包月、按量阶梯折扣等用户侧计价新形态。

**反目标（做了等于失败）**

- 价源或上游接口拉到错误数据后**悄悄改价**。任何价格写入都必须可见、可回溯。
- 渠道成本来源失效（上游接口挂了）时把成本当 0 或沿用到错得离谱的值而不提示。

## 3. 官方价同步

### 3.1 价源

| 格式 | 默认地址 | 内容 |
| --- | --- | --- |
| `basellm`（**新默认**） | `https://basellm.github.io/llm-metadata/api/newapi/ratio_config-v1-base.json` | `data.billing_expr[model]` 计费表达式 |
| `models_dev` | `https://models.dev/api.json` | 各厂商模型 `cost.{input,output,cache_read,cache_write}`（USD / 1M） |
| `litellm` | LiteLLM `model_prices_and_context_window.json` | 每 token 美元价 |
| `openrouter` | `https://openrouter.ai/api/v1/models` | 每 token 美元价 |
| `netonapi` | 自定义 | 与批量导入同格式 |

配置仍是 `gateway.pricing.source_format` / `gateway.pricing.source_url`，默认改为 `basellm`。

### 3.2 basellm 计费表达式

实测形状（2026-10-21 抓取，全部 `billing_mode = tiered_expr`）：

```
tier("standard", p * 2.5 + cr * 1.25 + c * 10)
len <= 200000 ? tier("0_200k", p * 1.25 + cr * 0.125 + c * 10) : tier("200k_plus", p * 2.5 + cr * 0.25 + c * 15)
```

变量：`p` 输入、`c` 输出、`cr` 缓存读、`cc` 缓存写、`cc1h` 1 小时缓存写，系数单位 USD / 1M tokens；`len` 为输入 token 数。

解析只接受这两种形状（单档 `tier(...)`、`len <= N ? tier(...) : <同形状>` 可嵌套），其余形状标记为「无法解析」列在校准结果里，不写入。`cc1h` 暂不计价（记入备注）。

### 3.3 分档价格

`gateway_model_prices` 增加 `tiers`（TEXT，JSON，可空）：

```json
[{"maxPromptTokens": 200000, "input": "1.25", "output": "10", "cacheRead": "0.125", "cacheWrite": "0"},
 {"maxPromptTokens": null,   "input": "2.5",  "output": "15", "cacheRead": "0.25",  "cacheWrite": "0"}]
```

- 有 `tiers` 时按本次请求的**总输入 token 数**（新输入 + 缓存读 + 缓存写，与厂商长上下文门槛口径一致）选第一个 `maxPromptTokens ≥ 总输入` 的档；`null` 表示不封顶。没有 `tiers` 时用现有四个价格字段（等价于单档）。
- 预留按**最贵那一档**估上界；结算按实际输入 token 数选档。
- 售价规则不变：加价率作用于每一档；固定售价（`sale_override`）只覆盖基础档，存在分档时管理台提示「固定售价不作用于长上下文档」。
- 定价快照记录所用档位，结算、对账可解释。

### 3.4 定时同步

现有定时任务 `gateway-price-sync` 改为：按 `gateway.pricing.source_*` 拉取 → 生成差异 → **不写库**，有差异时写一条「待确认的调价」并在概览与告警脚本里提示；运营在「校准官方价」里确认后写入（现有流程）。环境变量 `NETONAPI_PRICE_SYNC_URL` / `_OVERWRITE` 删除（启动时若设置则报错提示改用后台配置）。

## 4. 渠道实际成本

### 4.1 数据

渠道表增加：

| 列 | 含义 |
| --- | --- |
| `cost_source` | `official`（官方价 × 折扣）/ `upstream`（上游价目接口）/ `manual`（按模型手填）；新建渠道必填，不设默认 |
| `upstream_pricing_format` | `newapi`（new-api / one-api 类）/ `openrouter`；`cost_source = upstream` 时必填。渠道类型（`openai_compatible` 等）区分不了中转站与官方，所以单设此字段 |
| `upstream_pricing_url` | 上游价目接口地址；空 = 按格式推断（`newapi`：`{baseUrl}/api/pricing`；`openrouter`：`{baseUrl}/v1/models`） |
| `upstream_group_ratio` | 我们这把 Key 在上游所属分组的倍率（new-api 类上游的分组倍率；上游没有分组填 `1`）；`cost_source = upstream` 时必填 |
| `upstream_credit_rate` | 充值汇率：上游 1 美元额度实际花我们多少美元（如 ¥1 买 $1 额度、汇率 7.2 时约 `0.139`；¥7.2 买 $1 即 `1`）；`cost_source = upstream` 时必填 |
| `cost_synced_at` / `cost_sync_error` | 最近一次拉取时间与失败原因 |

`cost_discount`（成本折扣 = 实际成本 ÷ 官方价）在新建渠道时同样必填。

**成本设置必须由管理员明确给出，后端不补默认值**：上游几乎不可能按官方价 1:1 计费，静默补 `1` 等于把成本记成官方价，毛利体检与亏本拦截全部失真。确实是 1:1 时可以填 `1`，但必须是管理员填的。

- 新建：`cost_source`、`cost_discount` 必填；`cost_source = upstream` 时 `upstream_pricing_format`、`upstream_group_ratio`、`upstream_credit_rate` 也必填（`upstream_pricing_url` 可空，按格式推断）。
- 修改：没带的项保持原值；带了空值视同没填，按必填拒绝。从别的来源改成 `upstream` 时，分组倍率与充值汇率必须重新给出（库里原来的 `1` 只是占位）。
- 校验顺序固定为 来源 → 折扣 → 格式 → 分组倍率 → 充值汇率，一次报一个机器码：`CHANNEL_COST_SOURCE_REQUIRED`、`CHANNEL_COST_DISCOUNT_REQUIRED`、`CHANNEL_PRICING_FORMAT_REQUIRED`、`CHANNEL_UPSTREAM_GROUP_RATIO_REQUIRED`、`CHANNEL_UPSTREAM_CREDIT_RATE_REQUIRED`。
- 已有渠道的库值不迁移（它们是当初的默认值，由运营在渠道页逐个核对改正）。

新表 `gateway_channel_model_costs`（渠道 × 模型的实际成本价）：

| 列 | 含义 |
| --- | --- |
| `channel_id`, `model` | 主键；`model` 是**请求模型名**（对外模型） |
| `input_price` … `cache_write_price`, `per_request_price`, `tiers` | 与模型价格同单位 |
| `source` | `upstream` / `manual` |
| `updated_at` | |

### 4.2 成本解析顺序

结算、体检、报表取某渠道承载某模型的成本时：

1. `gateway_channel_model_costs` 有该行 → 用它（不再乘渠道折扣）；
2. 否则 → 实际上游模型（模型映射目标，没有价格行则请求模型）的官方价 × `cost_discount`（现行规则）。

`cost_source = upstream` 但某模型在上游接口里没有 → 走第 2 条，并在体检里标「上游未报价，按官方价估算」。

### 4.3 上游价目接口

- **new-api / one-api**：`GET /api/pricing`，`data[]` 含 `model_name`、`quota_type`（0 按量 / 1 按次）、`model_ratio`、`completion_ratio`、`cache_ratio`、`create_cache_ratio`、`model_price`。换算：输入价 = `model_ratio × 2`（USD / 1M，new-api 倍率 1 = $0.002 / 1K），输出价 = 输入价 × `completion_ratio`，缓存读 = 输入价 × `cache_ratio`，缓存写 = 输入价 × `create_cache_ratio`；按次 = `model_price` 美元 / 次。最后统一 × `upstream_group_ratio` × `upstream_credit_rate`，得到我们实际付出的美元。也接受 `/api/ratio_config` 形状。
- **OpenRouter**：`GET /v1/models`（带渠道 Key），`pricing.prompt / completion / input_cache_read / input_cache_write` 每 token 美元价，× `upstream_credit_rate`。
- **分档**：上游只报单档价、而该模型的官方价有 `tiers` 时，按官方各档相对基础档的比例放大上游价，生成同结构的分档成本（上游价 × 官方第 n 档 ÷ 官方基础档，逐项）。避免长上下文按基础档低估成本。
- 上游返回的是**它的**模型名：按渠道 `model_mapping` 反查对外模型名（映射目标 → 请求模型名）；没映射的同名对应。

### 4.4 同步与变动

- 定时任务 `gateway-channel-cost-sync`（默认每 6 小时）逐个拉 `cost_source = upstream` 的渠道；后台可手动立即拉。
- 拉取失败：保留上次成本，写 `cost_sync_error`，体检标黄「成本数据过期」；连续失败超过 24 小时进告警脚本。
- 成本变动写入调价记录（同 `gateway_price_history`，`action = channel_cost`，记录渠道 id）。上游成本是**我们付出的钱**，以上游为准，正常变动自动写入。
- **异常变动先挂起**：任一项单次涨或跌超过 50%，或变成 0，不写入成本表，而是在调价记录里记一条 `status = pending` 的变动。挂起期间结算、体检继续用旧值；运营在渠道「成本」抽屉的「待确认变动」里接受（写入）或忽略。首次同步（没有旧值）不设涨跌门槛，只有 0 价挂起。
- **上游不再报价**：某模型从上游价目里消失时保留旧成本行，标记「上游已不报价」（体检提示），不自动删除；运营可手动删除该行，回到官方价 × 折扣。

## 5. 管理界面

- **渠道页**：成本来源选择、上游价目接口地址、上游分组倍率、最近同步状态与「立即同步」；「成本」抽屉列出本渠道每个对外模型的实际成本价（可手填）。
  - 成本各项都标必填星号、不预填任何值；成本来源下拉默认是「请选择」。价目格式 / 地址 / 上游分组倍率 / 充值汇率只在成本来源选「上游价目接口」时出现。
  - 每项在输入框下方写明用途与填法（以界面语言包为准）：成本来源决定每次调用记多少成本（算毛利、拦截亏本定价）；成本折扣 = 实际成本 ÷ 官方价（1 = 同官方价，0.6 = 6 折，1.1 = 贵 10%），选「官方价 × 折扣」时算全部模型，选另外两种时只给拉不到上游价、也没手填的模型兜底；上游分组倍率举 openlux Azure-Gpt-2 = 0.1 为例，没有分组填 1；充值汇率举 ¥1 买 $1（≈ 0.139）与 ¥7.2 买 $1（= 1）为例。
- **对外模型页**（价格页的新视图）：每个对外模型一行，展开看承载它的渠道：优先级、权重、实际成本价、健康状态（启用 / 冷却 / 挂起 / 测速），以及按当前售价与分组的毛利。
- **体检新增提示**：
  - 优先级更高的渠道比同模型某个健康的、更低优先级渠道贵 → 提示（不自动调整）；
  - 上游未报价 / 成本数据过期；
  - 某对外模型只有官方渠道承载（没有便宜的中转）→ 信息提示。

## 6. 验收（harness）

| 场景 | 断言 |
| --- | --- |
| basellm 价源 | 单档与 `len <= N` 两档表达式解析正确；无法解析的形状列出、不写入；`cc1h` 不计价 |
| 分档计价 | 输入 token 跨过门槛时按高档结算；预留按最贵档；快照记录档位 |
| 定时同步只提示 | 定时任务发现差异不写库，生成待确认记录 |
| 上游价目接口 | 假 new-api `/api/pricing` → 渠道成本按倍率换算、× 上游分组倍率 × 上游额度单价；模型映射反查对外模型名；上游接口失败保留旧值并标过期；单次涨跌 > 50% 或变 0 挂起、旧值继续生效；模型消失保留旧值并提示；官方分档模型生成同结构分档成本 |
| 成本解析 | 有渠道成本行时结算用它、不乘折扣；没有时回到官方价 × 折扣 |
| 体检提示 | 高优先级渠道更贵时提示；上游未报价时提示 |
| 路由不变 | 现有 S2 等故障转移、优先级场景保持通过 |

## 7. 实施顺序

1. 价源：basellm / models.dev 解析，`tiers` 字段与分档计价，定时同步改为只提示。
2. 渠道成本：表结构、成本解析顺序接入结算与体检、上游价目接口与定时同步。
3. 管理界面：渠道成本、对外模型视图、体检新提示。

每步独立可测、可提交。

## 8. 已定的取舍（我做的决定，可推翻）

- 默认价源切到 basellm，与 new-api 一致；LiteLLM 等保留为可选。
- 分档只按输入 token 数，与 basellm 的 `len` 语义一致。
- 上游成本自动写入（它就是事实），只有大幅上涨先挂起；官方价变动则必须人工确认（它决定售价）。
- 固定售价不作用于长上下文档，避免在长上下文上亏本。
- 上游价目渠道的成本 = 上游价 × 上游分组倍率 × 上游额度单价，不再乘 `cost_discount`（该字段只用于 `official` 来源）。
- 上游成本单次涨跌超过 50% 或变成 0 时挂起待确认，其余自动写入；模型从上游消失时保留旧值并提示。
- 上游只报单档价时按官方分档比例生成分档成本。
- 分档门槛按总输入 token（含缓存读写）计。
- 新增 `upstream_pricing_format` 字段区分 new-api 类与 OpenRouter，不靠渠道类型或域名猜。
