# 网关计价与定价管理规范

> 标识：NETONAPI-GATEWAY-PRICING
> 日期：2026-10-20
> 状态：已在 `neton-application-module-gateway` 与 `neton-application-front-gateway` 实现；harness S20–S22、S72、S88–S92 覆盖。
> **§4.3 用户模型价目表（模型开放范围 + 分组定价 + 用户定价）为 2026-10-28 定稿、待实现**；§4.1 计费组按 Key 决定已于同日实现。
> 归属：NetonAPI。实现在 `neton-application-module-gateway`（计价、结算、定价管理接口）与 `neton-application-front-gateway`（模型定价、概览、计价分组、令牌页面）。

## 1. 目标与原则

对标国际中转平台的定价能力，但**管理操作要简单**：运营日常只需要在一页「模型定价」里看清每个模型赚不赚钱、改一个数就能修正。

1. **双轨计价，两轨分开**：收入轨（向用户收多少）与成本轨（我们付上游多少）各自计算、各自落库，毛利可逐笔解释。
2. **宁可拒绝，不可猜价**：未定价、定价非法、计费组配置悬空的请求一律在入口拒绝，绝不按 0 或默认倍率放行。
3. **默认跟随，例外最少**：大多数模型只设官方价、跟随全站加价率；单模型例外只在确有需要时设。
4. **看得见亏损**：保存价格时的闸门挡不住的亏法（分组折扣、渠道加价、模型映射）由定价体检逐组合核算并标出。
5. **价源只建议，不自动写**：官方价校准先给差异，运营勾选后才写入。

## 2. 术语与单位

| 术语 | 含义 | 单位 / 存储 |
| --- | --- | --- |
| 官方价 | 模型厂商公布的价格，也是成本基准 | `gateway_model_prices.{input,output,cache_read,cache_write}_price`，USD / 1M tokens，十进制字符串，非负，整数部分 ≤ 7 位 |
| 按次价 | 按请求计价的模型（图像等），设了它 token 价格不生效 | `per_request_price`，μUSD / 次 |
| 售价设置 | 单个模型的售价例外 | `sale_override`，JSON 文本，见 §3.2 |
| 全站加价率 | 售价 = 官方价 × 它 | 配置 `gateway.pricing.markup`，取值 (0, 100] |
| 最低毛利 | 实收不得低于成本 × 它 | 配置 `gateway.pricing.min_margin`，取值 (0, 100] |
| 平台价 | 对外标准价 = 上面的售价（官方价 × 加价率，或本模型固定价） | 公开价格页展示 |
| 分组倍率 | 平台对一个用户组的整体让利，对该组所有模型生效：实付 = 平台价 × 它 | `gateway_groups.ratio`，(0, 100] |
| 官方价折扣 | 定制定价的一种设法：实付 = 官方价 × 它（逐项、逐档），对客户表述为「官方价 N 折」 | 万分位整数，(0, 100] |
| 分组定价 / 用户定价 | 对「分组 × 模型」/「用户 × 模型」约定的价格：官方价折扣或固定价二选一（§4.3） | `gateway_group_model_prices` / `gateway_user_model_prices` |
| 用户模型价目表 | 服务端为每个「用户 × Key 的计费分组」算好并缓存的表：这个用户能用哪些模型、每个模型多少钱（§4.3） | 内存缓存，按定价纪元失效 |
| 渠道折扣 | 我们从这条渠道拿货的价格系数 | `gateway_channels.cost_discount`，(0, 100] |
| 实收 / 成本 | 一笔请求的收入 / 支出 | `charged` / `cost`，μUSD，整数 |

内部运算一律用整数：价格解析为 μUSD / 1M（×10⁶），倍率解析为万分位（×10⁴），乘法做溢出检查，计费路径不得静默回绕。

## 3. 计价规则

### 3.1 公式

```
售价(模型)  = 售价设置里的固定价  ?: 官方价 × (本模型加价率 ?: 全站加价率)      // 逐项
实收(请求)  = Σ 各项 tokens × 价目表里该模型的价格 / 10⁶（逐项向上取整）
价目表价格  = 用户定价(用户, 模型) ?: 分组定价(Key 的计费分组, 模型) ?: 平台价 × 分组倍率      // 逐项、逐档，§4.3
成本(请求)  = Σ 各项 tokens × 渠道成本价 / 10⁶（逐项向上取整）              // 渠道成本表有这个模型时（§12）
           = Σ 各项 tokens × 上游模型官方价 / 10⁶（逐项向上取整）× 渠道折扣  // 否则
```

- **分档价格**：价格行可带 `tiers`（按上下文长度分档，JSON `[{maxPromptTokens, input, output, cacheRead, cacheWrite}]`，最后一档 `maxPromptTokens = null`）。按本次请求的**总输入 token**（`max(prompt, 缓存读) + 缓存写`）选第一个 `maxPromptTokens ≥ 总输入` 的档，恰好等于门槛落低档；售价轨、成本轨各自选档。预留按售价最贵档估上界。有分档时四个单档价格列强制等于第一档。加价率作用于每一档；固定售价只覆盖第一档，长上下文档始终按加价率。命中档位记入 `relay tiered price applied` 日志。

- 各项：新输入 = prompt − min(缓存读, prompt)；缓存读；缓存写；输出。按次计价的模型直接取按次价。
- **成本按实际打给上游的模型算**：渠道 `model_mapping` 把请求模型映射成别的模型时，取映射目标的官方价；映射目标没有价格行（如 Azure 部署名）则用请求模型的官方价。
- 充值赠送、返利只进钱包台账，**不参与请求计价**。
- 价目表价格（及其来源：用户定价 / 分组定价 / 平台价 × 分组倍率）、成本基准在预留时冻结进定价快照，结算只按快照算；
  重试换渠道时刷新渠道折扣与成本基准。在途请求不受改价影响。
- 价目表按**请求模型**（用户调用的模型名）匹配，不按渠道映射后的上游模型名。
- 实收 < 成本的请求照常服务，记 `margin inversion` 告警，并计入概览的亏损统计（§6）。

### 3.2 售价设置（`sale_override`）

每个模型的售价方式三选一（管理台「调整售价」抽屉）：

| 方式 | 存储 | 行为 |
| --- | --- | --- |
| 跟随全站加价率（默认） | 空 | 售价 = 官方价 × 全站加价率 |
| 本模型加价率 | `{"markup":"1.3"}` | 售价 = 官方价 × 1.3，官方价变化时按比例跟随 |
| 自定义固定售价 | `{"input":"3","output":"15",...}` | 填了的项用固定价，没填的项按加价率；`perRequest` 可设按次价，`null` 表示关闭按次计价 |

- 固定价字段优先于 `markup`；任一字段非法（不是合法 JSON、价格串非法、加价率越界）直接拒绝保存，运行时遇到则请求返回 `500 model_pricing_invalid`。
- **保留语义**：`PUT /admin/gateway/price/update` 请求体不带 `saleOverride` 字段时保留原值，传空串才清除。只改官方价的表单不得冲掉售价设置。

### 3.3 全站参数

- `gateway.pricing.markup`、`gateway.pricing.min_margin` 是后台「配置管理」里的配置项，默认值取环境变量 `NETONAPI_SALE_MARKUP` / `NETONAPI_MIN_MARGIN`，都没有则为 `1`。
- 运行时读取带 10 秒缓存（`PricingPolicy`），改完最多 10 秒生效；配置值越界时回退默认值并记日志。

### 3.4 必须拒绝的情形

| 情形 | 响应 |
| --- | --- |
| 模型没有价格行 | `400 model_not_priced` |
| 按 token 计价、请求没带 `max_tokens`、模型也没配默认输出上限 | `400 output_cap_required` |
| 定价数据非法 | `500 model_pricing_invalid`（运营事故，ERROR 告警） |
| 计费组悬空（Key 绑定的分组已删、default 组缺失） | `500 billing_group_config_broken` |
| Key 上用户自选的分组已不再对该用户开放 | `403 billing_group_not_allowed` |

## 4. 用户等级与计费组

### 4.1 计费组由 API Key 决定

计费组同时决定**路由**（渠道 `groups` 里列了该组才可用）与**倍率**，两者必须是同一个组。
一次调用的计费组只取这把 Key 绑定的分组（`gateway_tokens.group_override`），没绑就是 `default`；不再从用户身上推导。
谁能把 Key 绑到哪个分组、调用时如何复核，见 [key-billing-group-design.md](key-billing-group-design.md)。

### 4.2 写入约束

- 计费组 `code` 是稳定身份，创建后不可改；被渠道、Key、用户专属分组引用时不可删。
- 运营指定 Key 的分组、开通用户专属分组都只能由管理端写，必须指向**存在的**组，否则拒绝（`GROUP_CODE_UNKNOWN`）；空值表示取消。
  用户自助只能在对他开放的范围里选（`TOKEN_GROUP_NOT_ALLOWED`），改不了运营指定的分组（`TOKEN_GROUP_LOCKED`）。
- 一个会员组最多映射一个计费组；管理台以下拉框选择会员组，拉不到会员组列表时退回数字输入。

### 4.3 用户模型价目表（模型开放范围 + 分组定价 + 用户定价）

**客户的比价锚点是官方价**：「Claude 官方价 $3，在这里 7 折」。所以对客户的定制价格一律相对**官方价**表述；
「平台整体打几折」是分组倍率的事，不在单个模型上重复表达。

#### 4.3.1 用户模型价目表

服务端为每个「用户 × Key 的计费分组」构建一张价目表并缓存。一次请求：

1. Key → 用户、计费分组（§4.1，路由组）；
2. 取这个「用户 × 分组」的价目表（缓存命中直接用，未命中现建）；
3. 请求模型**不在表里 → `404 model_not_found`**（与「没有这个模型」同一个回答，不暴露表外有什么）；
4. 在表里 → 表里这一行的价格冻结进定价快照、预留、按分组内承载它的渠道路由；成本按实际渠道算（§3.1、§12）。

同一张表也是 `/v1/models`、控制台「模型与价格」、建 Key 时「可用模型」的唯一数据来源：**看得到的就是调得通的，价格就是会扣的**。

**表里有哪些模型**：同时满足
- 已上架（模型目录，见 [model-catalog-design.md](model-catalog-design.md)）；
- **对这个用户开放**（§4.3.2）；
- 这个计费分组里有启用的渠道承载它（配置层面：渠道启用、列了该组、服务清单有该模型；临时冷却 / 熔断仍在路由时处理，不影响价目表）。

**每个模型的价格**（逐项：输入、输出、缓存读、缓存写，按次价；有长上下文分档的逐档），前一项有就用、不往下走：
1. 用户定价（这个用户 × 这个模型，且在有效期内）；
2. 分组定价（这个计费分组 × 这个模型，且在有效期内）；
3. 平台价 × 分组倍率。

**不叠加**：命中用户定价或分组定价时，价格就是约定的那个数，**不再乘分组倍率**——否则「官方 7 折」再乘 0.9 就不是 7 折了。

每一行记录：模型、各项 / 各档价格（μUSD / 1M）、按次价、默认输出上限、**来源**（USER / GROUP / PLATFORM）、
相当于官方价的折扣（展示用）、有效期截止（若有）。

#### 4.3.2 模型开放范围

模型不一定对所有人开放。每个上架模型有开放范围：
- **所有用户**（默认）；
- **指定范围**：只开放给列出的计费分组和 / 或用户（`gateway_model_grants(model, group_code?, user_id?)`，二者其一）。

不在范围内的用户，这个模型不进他的价目表：看不到、调不通。给某个用户设了用户定价**不会**自动开放模型——开放与定价是两件事，
管理台在给未开放的模型设价时提示「该用户目前看不到这个模型，是否一并开放」。

#### 4.3.3 分组定价与用户定价

两者结构相同，每条「分组 / 用户 × 模型」二选一：
- **官方价折扣**：填 0.7 = 官方价 7 折，逐项、逐档按官方价打折。官方价变了价格跟着变，「官方 7 折」的承诺一直成立。
- **固定价**：逐项填输入、输出、缓存读、缓存写（按次计价的模型填按次价；有长上下文分档的模型必须逐档填齐，缺档拒绝保存）。官方价变了不动。

可选**有效期**（开始、结束）：不在有效期内的条目在建表时跳过，回落到下一级；到期前 7 天在管理台概览提示。
`default` 组同样可以设分组定价（相当于「对所有用户的这个模型按官方价 N 折卖」），与本模型加价率并存时以分组定价为准。

存储：`gateway_group_model_prices(group_code, model, mode, discount, prices, starts_at, ends_at, remark)`、
`gateway_user_model_prices(user_id, model, mode, discount, prices, starts_at, ends_at, remark)`，各自 `(主体, model)` 唯一；
`mode` = `OFFICIAL_DISCOUNT` / `FIXED`，`prices` 为固定价 JSON（与 §3.2 固定售价同结构，含分档）。写入时校验分组 / 用户存在、模型有价格行。

#### 4.3.4 缓存与失效

- 价目表在进程内缓存，键为「用户 × 分组」，按需构建。
- **定价纪元**：任何会影响价目表的写入（价格行、上架、开放范围、分组倍率、分组定价、用户定价、渠道的启用 / 分组 / 服务清单、
  全站加价率、Key 的分组绑定与用户专属分组）都把纪元 +1。纪元存库，进程最多 5 秒读一次；缓存条目带建表时的纪元，纪元变了就重建。
  多实例部署时最长 5 秒看到新价；本实例上的管理端写入立即生效。
- 有效期边界不依赖写入：建表时记下最近的有效期边界，过了边界视同失效重建。

#### 4.3.5 保护与留痕

- **保存校验**（按真实会发生的组合）：
  分组定价对该分组内承载这个模型的每条渠道、用户定价对这个用户的 Key 当前所在分组内承载这个模型的每条渠道，逐个算毛利
  （实收 = 定价，成本 = 该渠道成本）。任一组合亏本 → 拒绝保存（`MODEL_PRICE_BELOW_COST`，返回亏本的渠道与金额）；
  低于「成本 × 最低毛利」→ 保存并提示偏薄；暂无渠道承载 → 保存并提示。
- **体检与校准**：定价体检（§5.2）按价目表价格核算每个分组定价、用户定价；官方价校准（§7）后，固定价高于新官方价、
  或因成本上涨亏本的分组 / 用户定价被标出提醒。官方价折扣天然跟随，不需要提醒。
- **调价记录**：每次新增、修改、删除写入 `gateway_price_history`（action = `group_price_set` / `group_price_delete` /
  `user_price_set` / `user_price_delete`、开放范围变更 `model_grant`），记主体、模型、前后值、操作人。
- 用户自助接口没有任何写定价与开放范围的路由。

#### 4.3.6 管理台与用户侧

- **用户定价**（新页面）：按用户 ID / 用户名查出他的价目表（按 Key 所在分组分开显示），每行：模型、官方价、他的价格、
  相当于官方价几折、来源（用户定价 / 分组定价 / 平台价）、最差毛利、有效期；可新增 / 修改 / 删除用户定价，可批量勾选模型统一设官方价折扣。
- **计价分组**编辑抽屉增加「按模型定价」：同一套表格，作用于这个分组。
- **模型与定价**：模型详情增加「开放范围」（所有用户 / 指定分组与用户）与「各分组价格」。
- **控制台「模型与价格」**：读自己的价目表，每个模型显示官方价、你的价格、相当于官方价几折；用户定价 / 分组定价标「协议价」，
  有有效期的显示到期时间。公开价格页只显示官方价与平台价。

## 5. 毛利保护

### 5.1 保存闸门

保存（新增、修改、导入、同步、校准）时，售价任一项低于「官方价 × 最低毛利」即拒绝（`SALE_PRICE_BELOW_COST_FLOOR`）。按次价只在售价与官方价都是按次时比较。

导入、同步、校准对已存在的模型，**按「新官方价 + 已有售价设置」合并后的结果**再过一次闸门；不过的行不写入，结果的 `heldForLoss` 列出模型名。典型场景：固定售价的模型官方涨价，直接覆盖会变成亏本价，必须等运营调完售价。

闸门只看售价与官方价，看不到分组倍率 < 1、渠道折扣 > 1 和模型映射，这些由 §5.2 的体检负责。
分组定价、用户定价在保存时按真实组合单独校验（§4.3.5）。

### 5.2 定价体检（`GET /admin/gateway/price/check`）

对每个已定价模型，枚举所有**实际会发生**的组合：启用中且承载该模型的渠道（`models` 列出或 `model_mapping` 键命中）× 该渠道 `groups` 里存在的计费组。每个组合逐项计算：

```
实收 = 该分组价目表里这个模型的价格（分组定价 ?: 平台价 × 分组倍率）       成本 = 上游模型官方价 × 渠道折扣（或渠道成本价，§12）
另对每条用户定价，按该用户 Key 所在分组的承载渠道单独核算。
毛利率 = (实收 − 成本) / 实收（万分位）
```

- 只比较成本 > 0 的项；按次计价的模型只比较按次价。
- 组合状态：实收 < 成本 → `LOSS`；实收 < 成本 × 最低毛利 → `THIN`；否则 `OK`。
- 模型状态取最差组合；没有任何组合 → `UNSERVED`；定价数据非法 → `INVALID`。
- 返回最差组合（分组、倍率、渠道、折扣、成本模型、最差的项）与最多 20 条问题组合，以及全站 LOSS / THIN / OK / UNSERVED 计数。

管理台「模型定价」以体检结果为主表：每行显示官方价、售价方式、售价、最坏毛利与所在组合、状态；可只看有问题的模型，问题组合给出修复方向（提高售价、调高分组倍率、调整分组 / 用户定价、换折扣更低的渠道）。

## 6. 利润看板

概览（`GET /admin/gateway/info/overview`）全部从用量日志现算：

- 按日、按模型、按用户的收入、成本、毛利；
- 按计费分组的收入、成本、毛利（看分组折扣让出了多少）；按价格来源（用户定价 / 分组定价 / 平台价）细分；
- 按渠道的收入、成本、毛利与测速；
- 统计期内 `cost > charged` 的请求笔数与亏损总额，不为 0 时页面顶部标红并链接到模型定价。

## 7. 官方价校准

### 7.1 价源

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `gateway.pricing.source_format` | `basellm` | `basellm`：GitHub basellm/llm-metadata 维护的官方价（new-api「官方倍率预设」同源）；`models_dev`：models.dev；`litellm`：LiteLLM 公开价目表；`openrouter`：OpenRouter `/api/v1/models`；`netonapi`：与批量导入同格式 |
| `gateway.pricing.source_url` | `https://basellm.github.io/llm-metadata/api/newapi/ratio_config-v1-base.json` | 可换为其它格式对应的地址 |

解析规则：

- basellm：`data.billing_expr[model]` 计费表达式，只认 `tier("<name>", p * a + cr * b + cc * c + c * d)` 与 `len <= N ? <tier> : <同形状>`（可嵌套、可加括号）。`p` 输入、`c` 输出、`cr` 缓存读、`cc` 缓存写，系数为 USD / 1M；`cc1h`（1 小时缓存写）不计价；`len <= N` 生成分档。其它写法一律列为 `UNPARSABLE`、不写入。basellm 给出分档信息：单档时清除我们原有的分档。
- models.dev：`{provider:{models:{id:{cost:{input,output,cache_read,cache_write}}}}}`（USD / 1M）；同名模型优先取 `openai` / `anthropic` / `google` / `deepseek`，否则按厂商名字典序取第一条。
- LiteLLM、OpenRouter、models.dev 不提供分档信息：校准时保留我们原有的分档。

- LiteLLM：每 token 美元价（`input_cost_per_token`、`output_cost_per_token`、`cache_read_input_token_cost`、`cache_creation_input_token_cost`）×10⁶ 换算为每 1M，按 μUSD 精度取整；跳过 `sample_spec` 与没有价格的条目；`max_output_tokens` 只用于新增模型的默认输出上限。
- OpenRouter：`pricing.prompt / completion / input_cache_read / input_cache_write`，同样按每 token 换算；负数（`-1` 表示可变价）不当官方价。

### 7.2 匹配与比对（`GET /admin/gateway/price/calibrate/preview`）

- 模型名先精确匹配价源条目；匹配不到时按去掉厂商前缀（`openai/`、`anthropic/` 等）的名字匹配，同名多条取 key 最短的一条，保证结果确定。
- 价源只覆盖它给出的项，没给的（常见是缓存价）保留现值，不清零。
- 每个模型给出状态：`SAME` 一致 / `CHANGED` 有变化（附最大涨跌幅）/ `NOT_FOUND` 价源没有 / `UNPARSABLE` 价源里的计费写法认不出 / `NEW` 渠道已承载但还没定价、价源里有价（建议新增）；`CHANGED` 同时标出按新官方价是否会因写死的售价而亏本（`wouldLose`）。
- 预览不写库。

### 7.3 应用（`POST /admin/gateway/price/calibrate/apply`）

- 只写请求里列出的模型；**重新拉取价源**，不采信前端回传的价格。
- 走批量导入同一路径：整批校验、§5.1 的合并闸门、调价记录（`action=calibrate`）、`source=calibrate`。
- 管理台默认勾选「有变化且不会亏本」与「建议新增」，会亏本的不默认勾选。

### 7.4 定时检查

定时任务 `gateway-price-sync`（每天 03:10）与管理台「校准官方价」抽屉都调用 `POST /admin/gateway/price/sync`：按 §7.1 的价源做一次预览，**只记录结果、不写价格表**（表 `gateway_price_sync_runs`：时间、价源、changed、new_models、unparsable、error）。最近一次的「changed + new_models」出现在概览（`priceSync`）、`/metrics` 的 `netonapi_price_pending_changes`、告警脚本的 `prices` 检查项；运营确认后按 §7.3 写入。旧的 `NETONAPI_PRICE_SYNC_URL` / `NETONAPI_PRICE_SYNC_OVERWRITE` 已删除，设着则启动报错。

## 8. 调价记录

- 表 `gateway_price_history`（V018，V020 加 `channel_id`、`status`）：模型、动作（create / update / delete / import / sync / calibrate / channel_cost）、变化前后的价格快照（输入、输出、缓存读、缓存写、按次、售价设置、分档）、操作人、时间；渠道成本变动记渠道 id，`status` 为 applied / pending（待确认）/ ignored。
- 只在价格或售价设置**真的变化**时记录；同步时价源与库内一致不记。
- 记录写入失败不影响调价本身。
- `GET /admin/gateway/price/history?model=` 返回最近 50 条；管理台每行「调价记录」展示只变化的字段。

## 9. 接口与权限

| 接口 | 权限 |
| --- | --- |
| `GET /admin/gateway/price/list`、`get/{id}`、`check`、`calibrate/preview`、`history`、`sync/last`；`POST /admin/gateway/price/sync`（检查价源，不改价） | `gateway:price:query` |
| `POST /admin/gateway/price/create`、`import` | `gateway:price:create` |
| `PUT /admin/gateway/price/update`、`POST /admin/gateway/price/calibrate/apply` | `gateway:price:update` |
| `DELETE /admin/gateway/price/delete/{id}` | `gateway:price:delete` |
| `PUT /admin/gateway/token/update-group?id=&groupCode=` | `gateway:token:update` |
| `GET /admin/gateway/pricebook?userId=`（某用户各分组的价目表）、`GET /admin/gateway/group-price/list?groupCode=`、`GET /admin/gateway/user-price/list?userId=`、`GET /admin/gateway/model-grant/list?model=`（§4.3，待实现） | `gateway:price:query` |
| `PUT /admin/gateway/group-price`、`DELETE /admin/gateway/group-price?groupCode=&model=`、`PUT /admin/gateway/user-price`、`DELETE /admin/gateway/user-price?userId=&model=`、`PUT /admin/gateway/model-grant`（设置开放范围）（§4.3，待实现） | `gateway:price:update` |
| `GET /app/gateway/model/mine`（自己的价目表，§4.3，待实现） | 登录用户 |
| `GET /admin/gateway/channel-cost/list?channelId=` | `gateway:channel:query` |
| `POST /admin/gateway/channel-cost/sync?channelId=`、`PUT /channel-cost/manual`、`DELETE /channel-cost/delete?channelId=&model=`、`POST /channel-cost/pending/{id}/accept`、`/ignore` | `gateway:channel:update` |
| `GET /app/gateway/model/list`（公开价格，匿名） | 无；只列有启用渠道承载且已定价的模型，价格为售价（未乘分组倍率） |

## 10. 验收

| 场景 | 断言 |
| --- | --- |
| S20–S22 | 双轨计价、保存闸门、全站加价率与最低毛利 |
| S88 | VIP 倍率使实收低于成本时体检判 `LOSS`、最差组合为该分组；本模型加价率修复后转 `OK`；只改官方价保留售价设置；固定售价下同步更高官方价被扣下；渠道映射到便宜模型时体检与结算都按映射目标计成本 |
| S72 | 定时检查只记差异不改价；`/metrics` 给出待确认数；设了已删除的 `NETONAPI_PRICE_SYNC_URL` 拒绝启动 |
| S89 | basellm 价源识别 `CHANGED` / `SAME` / `UNPARSABLE` / `NEW`；应用只写勾选的模型并记 `calibrate` 记录；models.dev 同名取厂商自营；OpenRouter 厂商前缀匹配 |
| S90 | 渠道折扣 > 1 的请求计入概览亏损；分组与渠道收入可见；令牌专属分组拒绝不存在的组、可设置、可取消 |
| S91 | 总输入跨过门槛按高档收费与计成本；预留按最贵档 |
| 待实现（§4.3） | 价目表：不在表里的模型 404，`/v1/models` 与控制台列表与之一致；优先级 用户定价 > 分组定价 > 平台价 × 分组倍率，命中定价时不乘分组倍率；官方价折扣逐项逐档跟随官方价、固定价不跟随；有效期外回落；开放范围为指定范围时未授权用户看不到也调不通，设用户定价不自动开放；定价纪元变更后最长 5 秒生效、本实例写入立即生效；在途请求按冻结快照结算；保存时亏本拒绝、偏薄提示；校准后固定价超官方价或亏本被标出；调价记录留痕；用户自助接口无写路由 |
| S92 | 渠道成本行优先且不乘折扣；上游价目格式必填；new-api `/api/pricing` 换算 × 上游分组倍率 × 额度单价；涨幅 > 50% 挂起、不重复挂起、接受后生效；上游下架标过期；上游报错保留旧值；对外模型视图（展示名、渠道按优先级、高优先级更贵提示） |

## 11. 未做与后续

- 上游账单自动对账（拿上游账单或余额变化与我们记录的成本比对）。
- 包月套餐、按用量阶梯折扣等用户侧计价形态。
- 路由按成本自动排序（有意不做：路由由优先级决定，成本只提示，见 §12.4）。

## 12. 渠道实际成本

设计见 [channel-cost-and-price-sync-design.md](./channel-cost-and-price-sync-design.md)。

### 12.1 成本来源

渠道 `cost_source`：`official`（默认，官方价 × `cost_discount`）/ `upstream`（上游价目接口，`upstream_pricing_format` 必填：`newapi` 或 `openrouter`）/ `manual`（按模型手填）。渠道成本表 `gateway_channel_model_costs`（渠道 × 请求模型，唯一）有这一行时成本按它计、**不乘折扣**；没有时退回官方价 × 折扣。

### 12.2 上游价目换算

- new-api / one-api：`/api/pricing`（或 `/api/ratio_config`），输入价 = `model_ratio × 2`、输出 = 输入 × `completion_ratio`、缓存读 / 写 = 输入 × `cache_ratio` / `create_cache_ratio`，按次 = `model_price` 美元。`success != true` 视为失败。
- OpenRouter：`/v1/models`（带渠道 Key），每 token 美元价 ×10⁶；`-1` 跳过。
- 最终成本 = 上游价 × `upstream_group_ratio` × `upstream_credit_rate`（1 美元上游额度实际花我们多少美元）。
- 上游模型名经渠道 `model_mapping` 反查对外模型名（值等于它的所有键）+ 渠道 `models` 里同名者。
- 官方价有分档而上游只报单档：按官方各档相对第一档的比例放大上游价。
- 地址留空时按格式推断：new-api 去掉末尾 `/v1` 再拼 `/api/pricing`；OpenRouter 以 `/v1` 结尾拼 `/models`，否则拼 `/v1/models`。

### 12.3 同步规则

定时任务 `gateway-channel-cost-sync`（每 6 小时）与渠道「成本」抽屉的「立即同步」：

- 正常变动直接写入，记调价记录（`action = channel_cost`）。
- 任一项单次涨跌超过 50%、或从非 0 变 0、或新价全为 0 → 不写入，记 `status = pending`；同一变动不重复挂起；挂起期间按旧值结算；接受后写入、忽略则丢弃。首次同步只有全 0 挂起。
- 上游不再报的模型保留旧值并标 `stale`，体检提示「成本过期」。
- 拉取失败：成本表不动，记 `cost_sync_error`。
- 手填（`source = manual`）的行同步时不覆盖。

### 12.4 对外模型视图与提示

- 价格行可设展示名 `display_name`（如「Opus 5.5」），管理台、控制台与公开价格页显示它，模型 ID 作为副标题。
- 体检每个模型列出承载渠道（按优先级从高到低）：优先级、权重、健康状态、实际成本（基础档，已乘折扣）、成本来源。
- 提示（不改路由）：`PRIORITY_COST_INVERSION` 有更高优先级渠道比健康的更低优先级渠道贵；`UPSTREAM_NOT_PRICED` 上游价目渠道没报这个模型；`COST_STALE` 成本已过期；`OFFICIAL_ONLY` 只有按官方原价计成本的渠道。健康 = 启用、不在冷却、最近测速未失败。
