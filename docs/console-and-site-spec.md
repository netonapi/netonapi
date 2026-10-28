# 门户与用户控制台规范（2026-10-28）

站点在 `netonapi-client`（Next.js）：公开页面在根路径，控制台在 `/console`（需登录）。站点名由后台配置，不写死。

## 1. 公开页面（未登录可看，可缓存、可收录）

- **首页 `/`**：AI 风格视觉，主打「安全、稳定」，**不写「多上游」之类把底层线路当卖点的话**。卖点每一条都必须对应系统里真实存在的能力：
  - 安全：TLS；上游凭证加密落库；API Key 只存哈希、可设额度 / 模型 / IP / 有效期；后台两步验证、IP 准入与操作审计；
  - 稳定：失败自动重试并切换；Key 连续失败自动隔离；按 Key 的 RPM / TPM / 并发限制；原生流式。
  - 「热门模型」取公开模型目录前几个（取不到就不显示这块），接入示例用网关对外地址（`NETONAPI_PUBLIC_API_URL`，没配时写占位）。
- **价格页 `/pricing`**：公开模型目录（`/app/gateway/model/list`：只列已上架且有启用上游的模型，见 [model-catalog-design.md](model-catalog-design.md)），价格是实际扣费的售价。缓存 30 秒。
- 两页共用门户页头页脚，页头正确显示登录状态（已登录显示用户菜单与「进入控制台」）。

## 2. 控制台

导航：工作台；API（API Key、模型与价格、用量与额度）；账户（充值与流水、邀请好友）。

- **工作台**（控制台首页）：余额与消费、接入方式（OpenAI / Anthropic / curl 三种写法）、近 7 天每日消费柱状图、模型消费分布、最近调用。
  每块独立取数、独立出错；可用余额低于 $1 提醒充值；新用户（没充值 / 没建 Key / 没调用过）先看到三步引导与推荐模型。
- **API Key**：见 [api-key-spec.md](api-key-spec.md)（明文只显示一次的模态弹窗、计费分组选择）。
- **充值与流水**：见 [recharge-spec.md](recharge-spec.md)（先选金额、再在对话框选支付方式；没开通显示「请联系客户经理为您充值」；没有模拟充值）。

## 3. 管理台相关页面

- 模型与定价：上架 / 价格库、服务上游（[model-catalog-design.md](model-catalog-design.md)）。
- 上游渠道：新建时可直接填 Key；「拉取模型」只记录上游支持的模型。
- API 令牌：监管，不能建 Key（[api-key-spec.md](api-key-spec.md)）。
- 计价分组 / 用户专属分组（[key-billing-group-design.md](key-billing-group-design.md)）。
- 用量日志 → 手动充值与撤回；支付订单 → 同步支付状态（[recharge-spec.md](recharge-spec.md)）。

## 4. 未纳入本期

- 控制台「模型与价格」按分组分别显示单价（目前显示基础售价）。用户模型价目表（[gateway-pricing-spec.md §4.3](gateway-pricing-spec.md)）
  上线后，控制台「模型与价格」改读自己的价目表：官方价、你的价格、相当于官方价几折，协议价单独标注。
