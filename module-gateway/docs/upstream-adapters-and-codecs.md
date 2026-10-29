# 上游适配、跨协议保真与端点规范

状态：2026-09 实装；对应 NewGate 设计文档 §5 的落地细则。

## 1. 适配器模型

`ProtocolAdapter` 描述一种**上游协议**（`id`：openai / anthropic / gemini），负责：请求改写（模型映射、参数覆盖）、
usage 提取（非流式与流式累加器）、错误体形状。托管上游在同一协议之上只改鉴权、路径、body 与流帧，通过四个钩子覆盖：

| 钩子 | 默认 | Azure | Bedrock | Vertex |
|---|---|---|---|---|
| `upstreamPath(inbound, channel, model, stream)` | 按协议默认 | `/openai/deployments/{dep}/…?api-version=` | `/model/{id}/invoke[-with-response-stream]` | `/v1/projects/{p}/locations/{l}/publishers/{anthropic\|google}/models/{m}:…` |
| `upstreamBody(body, model, stream)` | 原样 | 原样 | 去 model/stream，加 `anthropic_version` | 去 model，加 `anthropic_version`（Gemini 原样） |
| `requestHeaders(key, method, url, body, channel)` | 静态头 | `api-key` | SigV4（签 url + body） | `Authorization: Bearer <OAuth>`（服务账号 JWT 换 token，缓存到过期前 60s） |
| `byteStreamDecoder()` | null（上游是 SSE） | — | AWS event-stream 二进制帧 → SSE | — |

`by` 委托的坑：Kotlin 接口默认方法经 `object X : ProtocolAdapter by Y` 委托后，方法体内的 `this` 是 Y。
凡默认实现里回调了本对象其它方法的钩子（`requestHeaders` 默认调 `upstreamHeaders`），托管适配器必须显式覆盖。

渠道字段：`type`、`base_url`、`api_version`（Azure）、`provider_config` JSON（Vertex 的 project/location、Bedrock 可选 region）、
Key（Bedrock 为 `AK:SK[:TOKEN]`，Vertex 为服务账号 JSON 或裸 access token）。

## 2. 跨协议保真矩阵

入站 → 上游经 hub（OpenAI ChatCompletions）转换；同协议恒等透传（仅模型映射双向改写）。

| 内容 | Anthropic ↔ OpenAI | Gemini ↔ OpenAI |
|---|---|---|
| 文本、system（含数组 / mid-conversation system） | ✓ | ✓（systemInstruction） |
| 工具定义 / 调用 / 结果（含并行、顺序、合并） | ✓；无 input_schema 的服务端工具丢弃 | ✓（functionCall/functionResponse，id 按名回配） |
| 图片 base64 / URL | ✓ | ✓（inlineData / fileData） |
| thinking / reasoning | thinking 块 ↔ `reasoning_content`；`thinking.enabled/adaptive` + `output_config.effort` ↔ `reasoning_effort` | thought 部分 ↔ `reasoning_content`；`thinkingConfig` ↔ `reasoning_effort` |
| 缓存用量 | `cache_read/creation_input_tokens` ↔ `prompt_tokens_details.cached_tokens`（prompt_tokens 为三项之和） | `cachedContentTokenCount` ↔ cached_tokens |
| 结构化输出 | 未做 | `response_format` ↔ `responseMimeType/responseSchema` |
| 流式 | 事件序列严格先关后开；thinking 块收尾补 `signature_delta` | functionCall 攒到整只再放出 |
| 有损项 | `cache_control`（OpenAI 无对应）、Anthropic 服务端工具 | 多候选（n>1）、thought signature |

上游错误体按客户端协议改写（`errorBodyFor`），同协议原样透传；`anthropic-beta` / `OpenAI-Beta` 头只在同协议时透传。

## 3. 端点与计费规则

| 端点 | 能力 | 上游限制 | usage 来源 | 预留 |
|---|---|---|---|---|
| chat / messages / generateContent | chat | 任意 | 上游原生 | 输入字节上界 + 输出上限（max_tokens 或默认上限） |
| responses | chat | 仅 OpenAI 系 | `input_/output_tokens`，流式取 `response.completed` | 同上（`max_output_tokens`） |
| embeddings | embeddings | 任意 | prompt_tokens 或 total_tokens | 同上 |
| images | images | 仅 OpenAI 系 | 多半无 usage → 按次价 | 按次 |
| rerank | rerank | 仅 OpenAI 系 | `total_tokens` 计输入 | 输入 |
| audio/speech | audio | 仅 OpenAI 系 | 请求侧：`input` 字符数计输入 | 输入 |
| audio/transcriptions, translations | audio | 仅 OpenAI 系 | 上游给则用，否则按次价 | 输入（multipart 字节） |

按次计价模型非流式 2xx 无 usage 视为完整（ok）；流式截断永远 partial。

## 4. 运维任务与指标

- `gateway-channel-probe`（*/5）：并发 4 探测启用渠道，写 `last_probe_*`。
- `gateway-price-sync`（每日 03:10）：`NETONAPI_PRICE_SYNC_URL` → `PriceImportLogic`（整批校验、按 model upsert）。
- `/metrics`：从日志表现算的 Prometheus 指标；`NETONAPI_METRICS_TOKEN` 可选鉴权。

## 5. 性能注意点

- 流式路径每块都过：模型名正则按上游名缓存；event-stream 解帧用增长缓冲 + 偏移；UTF-8 长度只算不拷。
- 定价导入一次读全表建索引；概览 SQL 全部 `SUM(...)::BIGINT`（sqlx4k 解不了 NUMERIC 会 abort 进程）。
