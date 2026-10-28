# 转发错误处理（2026-10-29）

原则：**上游与我们自己的配置问题，不让调用方背锅，也不把内部信息透给调用方。**

| 情形 | 处理 | 调用方看到 |
|---|---|---|
| 上游 400 等请求类 4xx（参数不对等） | 原样回给调用方（转成入站协议的错误格式），不计 Key 失败 | 上游的报错，便于修正请求 |
| 上游 401 / 402 / 403（Key 失效、上游分组没有该模型、上游余额不足） | 计 Key 失败（连续失败自动禁用）、记日志（含上游报错前 300 字）、**换下一条渠道** | 有渠道成功就是正常结果；全部失败回 `503 no_available_channel` + 通用文案 |
| 上游 429 | 渠道冷却退避，换下一条渠道 | 同上 |
| 上游 5xx、网络错误、超时 | 计 Key 失败，换下一条渠道 | 同上 |
| 出站策略拦截（渠道地址指向内网等） | 不计 Key 失败，换下一条渠道；拦截码（如 `UPSTREAM_URL_PRIVATE_ADDRESS`）只进日志 | 同上 |
| 全部候选在首字节前失败 | 释放预留（没产生用量），不扣费 | `503 no_available_channel`，文案不含渠道号、上游报错、内部拦截码 |

失败细节（哪条渠道、上游回了什么、拦截码）只在服务端日志里：`relay upstream account error`、`relay target refused`、
`relay retry`、`relay all channels failed`。

## 跨协议转发的路径

请求体转成上游协议后，路径也必须是上游协议的：OpenAI 系上游一律打 `/v1/chat/completions`；Anthropic 上游打 `/v1/messages`；
Gemini 上游打 `/v1beta/models/{model}:generateContent`（流式 `streamGenerateContent?alt=sse`）。
回归测试：`CrossProtocolPathTest`；线上演练「用 Anthropic 格式调 OpenAI 渠道的模型」核对模拟上游实际收到的路径。
