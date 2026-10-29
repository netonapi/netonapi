package model

import kotlinx.serialization.Serializable
import neton.database.annotations.*

/** 上游渠道。type: openai_compatible / anthropic / gemini / azure_openai（spec 决策 D）。 */
@Serializable
@Table("gateway_channels")
data class Channel(
    @Id val id: Long = 0,
    val name: String,
    val type: String,
    val baseUrl: String,
    /** 渠道分组（与用户分组求交集决定可用性），逗号分隔如 "default,vip" */
    val groups: String = "default",
    /**
     * 正在为我们服务的模型（逗号分隔）：路由按它选渠道。由模型目录的「服务上游」写入（见 [logic.ModelRoutes]），
     * 不再由「拉取模型」整批并入。
     */
    val models: String = "",
    /** 上游支持的模型（V023，「拉取模型」写入，逗号分隔）：只是给模型挑上游时的候选，不让任何模型对外。 */
    val supportedModels: String = "",
    /**
     * 该渠道真正能服务的端点能力（逗号分隔，如 "chat,embeddings"）。
     * 只声明模型不够：embeddings 打到只会 chat 的上游必然 404，而钱已预留、日志已脏、重试还撞同一堵墙。
     * 默认 "chat"——新能力必须显式声明，不按渠道类型猜（猜错的方向是静默把请求送进不支持的上游）。
     */
    val capabilities: String = "chat",
    /** 模型映射 JSON：{"请求名":"上游名"} */
    val modelMapping: String? = null,
    /** 请求参数覆盖 JSON */
    val paramOverride: String? = null,
    val priority: Int = 0,
    val weight: Int = 1,
    /** 1=启用 0=禁用 2=自动挂起（全部 Key 失效） */
    val status: Int = 1,
    /** TTFB 超时 ms */
    val ttfbTimeoutMs: Long = 30_000,
    /** 流式块间空闲超时 ms */
    val idleTimeoutMs: Long = 90_000,
    val proxyUrl: String? = null,
    /** Azure OpenAI 的 api-version（V008）；其它类型忽略。空 = 代码默认值。 */
    val apiVersion: String? = null,
    /** 托管上游的厂商参数 JSON（V011）：Vertex {"project","location"}，Bedrock 可选 {"region"} */
    val providerConfig: String? = null,
    /** 最近一次测速（V010，定时任务 gateway-channel-probe 或手动探测写入） */
    val lastProbeAt: Long? = null,
    val lastProbeMs: Long? = null,
    /** 1 = 通，0 = 不通，null = 没测过 */
    val lastProbeOk: Int? = null,
    val lastProbeError: String? = null,
    /** 429 冷却截止（epoch ms）：此刻之前该渠道不参与候选，避免立即重试被限流的上游 */
    val cooldownUntil: Long = 0,
    /** 成本折扣（如 "0.6" = 官方价 6 折），DECIMAL 字符串，毛利核算用（spec 决策 B2） */
    val costDiscount: String = "1.0",
    /** 成本来源（V020）：official = 官方价 × 折扣；upstream = 上游价目接口；manual = 按模型手填（渠道成本表）。 */
    val costSource: String = "official",
    /** 上游价目格式：newapi（new-api / one-api 类）/ openrouter；costSource = upstream 时必填。 */
    val upstreamPricingFormat: String? = null,
    /** 上游价目接口地址；空 = 按格式推断（newapi：{baseUrl}/api/pricing；openrouter：{baseUrl}/v1/models）。 */
    val upstreamPricingUrl: String? = null,
    /** 我们在上游平台所属分组的倍率（new-api 类上游）。 */
    val upstreamGroupRatio: String = "1",
    /** 上游额度单价：每 1 美元上游额度实际花我们多少美元。 */
    val upstreamCreditRate: String = "1",
    val costSyncedAt: Long? = null,
    val costSyncError: String? = null,
    /** 跨协议转换的损耗策略（V017）：allow / safe / strict。 */
    val lossPolicy: String = "allow",
    /** 1 = 解析模型名推理尾缀（V017），默认关闭。 */
    val reasoningSuffix: Int = 0,
    val remark: String? = null,
    @SoftDelete val deleted: Int = 0,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)
