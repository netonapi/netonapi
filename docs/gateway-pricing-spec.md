# 网关计价与定价管理规范

> 标识：NETONAPI-GATEWAY-PRICING
> 日期：2026-10-20
> 状态：已在 `neton-application-module-gateway` 与 `neton-application-front-gateway` 实现；harness S20–S22、S88–S90 覆盖。
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
| 分组倍率 | 不同等级用户的价格系数（VIP 0.9 即九折） | `gateway_groups.ratio`，(0, 100] |
| 渠道折扣 | 我们从这条渠道拿货的价格系数 | `gateway_channels.cost_discount`，(0, 100] |
| 实收 / 成本 | 一笔请求的收入 / 支出 | `charged` / `cost`，μUSD，整数 |

内部运算一律用整数：价格解析为 μUSD / 1M（×10⁶），倍率解析为万分位（×10⁴），乘法做溢出检查，计费路径不得静默回绕。

## 3. 计价规则

### 3.1 公式

```
售价(模型)  = 售价设置里的固定价  ?: 官方价 × (本模型加价率 ?: 全站加价率)      // 逐项
实收(请求)  = Σ 各项 tokens × 售价 / 10⁶（逐项向上取整）× 分组倍率
成本(请求)  = Σ 各项 tokens × 上游模型官方价 / 10⁶（逐项向上取整）× 渠道折扣
```

- 各项：新输入 = prompt − min(缓存读, prompt)；缓存读；缓存写；输出。按次计价的模型直接取按次价。
- **成本按实际打给上游的模型算**：渠道 `model_mapping` 把请求模型映射成别的模型时，取映射目标的官方价；映射目标没有价格行（如 Azure 部署名）则用请求模型的官方价。
- 充值赠送、返利只进钱包台账，**不参与请求计价**。
- 售价、分组倍率、成本基准在预留时冻结进定价快照，结算只按快照算；重试换渠道时刷新渠道折扣与成本基准。
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
| 计费组悬空（令牌 / 用户例外指向已删的组、default 组缺失） | `500 billing_group_config_broken` |
| 会员有所属分组但没映射计费组 | `403 billing_group_not_allowed` |

## 4. 用户等级与计费组

### 4.1 解析顺序

计费组同时决定**路由**（渠道 `groups` 里列了该组才可用）与**倍率**，两者必须解析到同一个组。前一层给出结果即停：

1. 令牌专属分组（`gateway_tokens.group_override`）
2. 用户例外（`gateway_group_overrides`，大客户谈判价）
3. 会员组映射（`gateway_groups.member_group_id`，需 `NETONAPI_MEMBER_GROUP_BILLING=true`）
4. `default` 组

### 4.2 写入约束

- 计费组 `code` 是稳定身份，创建后不可改；被渠道引用时不可删。
- 令牌专属分组、用户例外只能由管理端写，必须指向**存在的**组，否则拒绝（`GROUP_CODE_UNKNOWN`）；空值表示取消。用户自助接口不得改计费组。
- 一个会员组最多映射一个计费组；管理台以下拉框选择会员组，拉不到会员组列表时退回数字输入。

## 5. 毛利保护

### 5.1 保存闸门

保存（新增、修改、导入、同步、校准）时，售价任一项低于「官方价 × 最低毛利」即拒绝（`SALE_PRICE_BELOW_COST_FLOOR`）。按次价只在售价与官方价都是按次时比较。

导入、同步、校准对已存在的模型，**按「新官方价 + 已有售价设置」合并后的结果**再过一次闸门；不过的行不写入，结果的 `heldForLoss` 列出模型名。典型场景：固定售价的模型官方涨价，直接覆盖会变成亏本价，必须等运营调完售价。

闸门只看售价与官方价，看不到分组倍率 < 1、渠道折扣 > 1 和模型映射，这些由 §5.2 的体检负责。

### 5.2 定价体检（`GET /admin/gateway/price/check`）

对每个已定价模型，枚举所有**实际会发生**的组合：启用中且承载该模型的渠道（`models` 列出或 `model_mapping` 键命中）× 该渠道 `groups` 里存在的计费组。每个组合逐项计算：

```
实收 = 售价 × 分组倍率       成本 = 上游模型官方价 × 渠道折扣
毛利率 = (实收 − 成本) / 实收（万分位）
```

- 只比较成本 > 0 的项；按次计价的模型只比较按次价。
- 组合状态：实收 < 成本 → `LOSS`；实收 < 成本 × 最低毛利 → `THIN`；否则 `OK`。
- 模型状态取最差组合；没有任何组合 → `UNSERVED`；定价数据非法 → `INVALID`。
- 返回最差组合（分组、倍率、渠道、折扣、成本模型、最差的项）与最多 20 条问题组合，以及全站 LOSS / THIN / OK / UNSERVED 计数。

管理台「模型定价」以体检结果为主表：每行显示官方价、售价方式、售价、最坏毛利与所在组合、状态；可只看有问题的模型，问题组合给出修复方向（提高售价、调高分组倍率、换折扣更低的渠道）。

## 6. 利润看板

概览（`GET /admin/gateway/info/overview`）全部从用量日志现算：

- 按日、按模型、按用户的收入、成本、毛利；
- 按计费分组的收入、成本、毛利（看分组折扣让出了多少）；
- 按渠道的收入、成本、毛利与测速；
- 统计期内 `cost > charged` 的请求笔数与亏损总额，不为 0 时页面顶部标红并链接到模型定价。

## 7. 官方价校准

### 7.1 价源

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `gateway.pricing.source_format` | `litellm` | `litellm`：LiteLLM 公开价目表；`openrouter`：OpenRouter `/api/v1/models`；`netonapi`：与批量导入同格式 |
| `gateway.pricing.source_url` | LiteLLM `model_prices_and_context_window.json` 原始地址 | 可换 OpenRouter 或自建地址 |

解析规则：

- LiteLLM：每 token 美元价（`input_cost_per_token`、`output_cost_per_token`、`cache_read_input_token_cost`、`cache_creation_input_token_cost`）×10⁶ 换算为每 1M，按 μUSD 精度取整；跳过 `sample_spec` 与没有价格的条目；`max_output_tokens` 只用于新增模型的默认输出上限。
- OpenRouter：`pricing.prompt / completion / input_cache_read / input_cache_write`，同样按每 token 换算；负数（`-1` 表示可变价）不当官方价。

### 7.2 匹配与比对（`GET /admin/gateway/price/calibrate/preview`）

- 模型名先精确匹配价源条目；匹配不到时按去掉厂商前缀（`openai/`、`anthropic/` 等）的名字匹配，同名多条取 key 最短的一条，保证结果确定。
- 价源只覆盖它给出的项，没给的（常见是缓存价）保留现值，不清零。
- 每个模型给出状态：`SAME` 一致 / `CHANGED` 有变化（附最大涨跌幅）/ `NOT_FOUND` 价源没有 / `NEW` 渠道已承载但还没定价、价源里有价（建议新增）；`CHANGED` 同时标出按新官方价是否会因写死的售价而亏本（`wouldLose`）。
- 预览不写库。

### 7.3 应用（`POST /admin/gateway/price/calibrate/apply`）

- 只写请求里列出的模型；**重新拉取价源**，不采信前端回传的价格。
- 走批量导入同一路径：整批校验、§5.1 的合并闸门、调价记录（`action=calibrate`）、`source=calibrate`。
- 管理台默认勾选「有变化且不会亏本」与「建议新增」，会亏本的不默认勾选。

### 7.4 定时同步

定时任务 `gateway-price-sync`（每天 03:10）仍从环境变量 `NETONAPI_PRICE_SYNC_URL` 拉取并导入（`NETONAPI_PRICE_SYNC_OVERWRITE=false` 则只补新模型），同样受 §5.1 合并闸门约束。

## 8. 调价记录

- 表 `gateway_price_history`（V018）：模型、动作（create / update / delete / import / sync / calibrate）、变化前后的价格快照（输入、输出、缓存读、缓存写、按次、售价设置）、操作人、时间。
- 只在价格或售价设置**真的变化**时记录；同步时价源与库内一致不记。
- 记录写入失败不影响调价本身。
- `GET /admin/gateway/price/history?model=` 返回最近 50 条；管理台每行「调价记录」展示只变化的字段。

## 9. 接口与权限

| 接口 | 权限 |
| --- | --- |
| `GET /admin/gateway/price/list`、`get/{id}`、`check`、`calibrate/preview`、`history` | `gateway:price:query` |
| `POST /admin/gateway/price/create`、`import`、`sync` | `gateway:price:create` |
| `PUT /admin/gateway/price/update`、`POST /admin/gateway/price/calibrate/apply` | `gateway:price:update` |
| `DELETE /admin/gateway/price/delete/{id}` | `gateway:price:delete` |
| `PUT /admin/gateway/token/update-group?id=&groupCode=` | `gateway:token:update` |
| `GET /app/gateway/model/list`（公开价格，匿名） | 无；只列有启用渠道承载且已定价的模型，价格为售价（未乘分组倍率） |

## 10. 验收

| 场景 | 断言 |
| --- | --- |
| S20–S22 | 双轨计价、保存闸门、全站加价率与最低毛利 |
| S88 | VIP 倍率使实收低于成本时体检判 `LOSS`、最差组合为该分组；本模型加价率修复后转 `OK`；只改官方价保留售价设置；固定售价下同步更高官方价被扣下；渠道映射到便宜模型时体检与结算都按映射目标计成本 |
| S89 | LiteLLM 价源识别 `CHANGED` / `SAME` / `NEW`；应用只写勾选的模型并记 `calibrate` 记录；OpenRouter 厂商前缀匹配 |
| S90 | 渠道折扣 > 1 的请求计入概览亏损；分组与渠道收入可见；令牌专属分组拒绝不存在的组、可设置、可取消 |

## 11. 未做与后续

- 渠道 × 模型粒度的折扣（目前折扣按渠道一个值）。
- 按 token 量阶梯定价、包月套餐。
- 价源校准的定时提醒（目前只在打开校准时比对）。
