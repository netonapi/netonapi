# 计费分组由 API Key 决定（2026-10-28）

## 原则

网关 `/v1` 是独立的 `gateway` 路由组，只认 sk- Key（TokenGuard），与 platform 开放 API 的 `open` 路由组同一模式：
**凭据自己的配置决定它能调什么、按什么价格计费**。在 platform 里是 Client 的授权配置（ClientApi，可单独定价），
在网关里就是 API Key 的配置：可调用模型、预算、IP 白名单、限速，以及**计费分组**。

一次调用的分组只取这把 Key 绑定的分组，没绑就是 `default`。分组同时决定两件事：走哪些渠道（渠道的 `groups`）、
按什么倍率计价（分组的 `ratio`；分组还可以对单个模型设定制倍率覆盖，见 [gateway-pricing-spec.md §4.3](gateway-pricing-spec.md)）。不再按「用户例外 → 会员组 → default」从用户身上推导价格。

## 谁能把 Key 绑到哪个分组

- **用户自选**：建 / 改 Key 时在「可选分组」里选。可选分组 = `default` + 运营设为「用户可选」（`selectable = 1`）的
  + 运营单独开给这个用户的（`gateway_group_overrides`，管理台叫「用户专属分组」）
  + 他的会员组映射的（开了 `NETONAPI_MEMBER_GROUP_BILLING` 时）。
- **运营指定**：管理台「API 令牌」给某把 Key 指定分组（特价 / 专线），`group_assigned = 1`，用户改不了。
- 后台不能代用户建 Key（V024 已撤掉）；Key 的限速（RPM / TPM / 并发）只有运营能设。Key 的完整规则见 [api-key-spec.md](api-key-spec.md)。

## 调用时的校验

- Key 没绑分组 → `default`（必须存在，否则 500 配置损坏）。
- 运营指定的 → 直接生效。
- 用户自选的 → 每次调用复核此刻仍对这个用户开放：运营收回专属分组、关掉「用户可选」后立即 **403
  `billing_group_not_allowed`**，用户在控制台换分组即可。不会继续按当初选到的价格计费。
- 绑定的分组被删 / 悬空 → 500，不回落到 default。

用量日志的 `group_source`：`TOKEN_OVERRIDE`（运营指定）/ `TOKEN_SELECTED`（用户自选）/ `DEFAULT`。

## 迁移（V025）

- `gateway_groups.selectable`，`default` 置 1。
- `gateway_tokens.group_assigned`；已有 `group_override` 的 Key（此前只有运营能设）置 1。
- 有用户例外的用户：他还没绑分组的 Key 绑到那个分组（保持原来的计价）。之后用户例外只表示「额外可选」。
- 会员组映射此前只在开了 `NETONAPI_MEMBER_GROUP_BILLING` 时参与计价；开着该开关的部署，会员的 Key 迁移后回到
  `default`，需要会员在控制台把 Key 绑到会员组映射的分组（或运营批量指定）。

## 接口

- `GET /app/gateway/token/groups` → 当前用户可选的分组（code、名称、对外说明、倍率）。
- `POST /app/gateway/token/create`、`PUT /app/gateway/token/update` 带 `group`；不在可选范围 → `TOKEN_GROUP_NOT_ALLOWED`，
  改运营指定的分组 → `TOKEN_GROUP_LOCKED`。
- `PUT /admin/gateway/token/update-group?id=&groupCode=` 运营指定 / 取消指定。
- 分组管理 `selectable`、`description`（对外说明）。
