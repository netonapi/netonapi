# 线上演练规范（2026-10-28）

`harness/live-e2e.py` 在部署好的环境上按真实用户路径走一遍：管理员配渠道与模型目录 → 管理员给演练账号手动充值 →
用户建 Key → 三种协议（OpenAI / Anthropic / Gemini）流式与非流式调用、跨协议、Responses、向量 → 扣费对账 →
停用 Key、限速、余额不足、并发 20 路、上游 4xx。本地全量回归是 `harness/run.sh`（隔离库，不碰服务器）。

## 1. 模拟上游

服务器本机的 `harness/mock-provider.py`（systemd：`netonapi-mock-a` 19001 正常、`netonapi-mock-b` 19002 永远 500）。

- **用量必须可信**：输入 token 不超过请求字节数，输出不超过请求的输出上限（`max_tokens` / `max_completion_tokens` /
  `max_output_tokens` / `generationConfig.maxOutputTokens`）。网关按「请求字节数 + 输出上限」做保守预留，用量不可信的上游会让
  每笔结算都超出预留、刷出 `settlement OVERAGE` 告警——那会把正式环境里真正的告警淹没。演练期间 ERROR 日志应为零。
- 用量是固定值（与 `live-e2e.py` 的 `USAGE` 一致），演练逐笔核对「扣费 = 售价 × 上游报的用量」。

## 2. 账号

- 演练主账号：专用的 `e2emain`（密码在 `local/seoul/secrets.env`，不入库）。**不要用 hometest**：它是给人手动测试用的，
  可能被单独绑了分组（例如 test-openlux）。
- 余额不足账号 `e2epoor`：演练开始前把它的余额撤回到零。
- 额度一律由**管理员手动充值**（`/admin/gateway/quota/grant`）；模拟充值接口已删除，演练断言它返回 404。

## 3. 演练会改动的数据

- 每次删除并重建 `e2e-*` 四个渠道（两条 OpenAI 兼容、一条 Anthropic、一条 Gemini，均指向模拟上游）。
- 把 5 个演练模型（gpt-4o-mini、gpt-4.1、claude-sonnet-4、gemini-2.5-flash、text-embedding-3-small）定价、上架并指定服务上游。
  **它们会出现在公开价格页与 `/v1/models`，背后是模拟上游**。正式对外开放前，运营要上架真实模型、勾选真实上游，并下架这 5 个演练模型
  （或不再在正式环境跑演练）——待决定。

## 4. 运行

```bash
set -a && . local/seoul/secrets.env && set +a
ADMIN_USER=admin ADMIN_PASSWORD=… MEMBER_USER=$E2E_MAIN_USERNAME MEMBER_PASSWORD=$E2E_MAIN_PASSWORD \
MOCK_STATS_CMD="ssh -o ControlPath=$HOME/.ssh/cm/seoul root@<host>" python3 -B harness/live-e2e.py
```

后端出站需放行 127.0.0.1 与 19001 / 19002（`NETON_EGRESS__ALLOWED_HOSTS` / `NETON_EGRESS__ALLOWED_PORTS`）。
