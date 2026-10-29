package logic

import model.Channel
import kotlin.random.Random

/** 渠道路由纯函数：端点能力 + 候选过滤 + priority 分层 + 层内 weight 加权随机 + 重试排除。 */
object RelayRouter {

    /** 对话能力：/v1/chat/completions、/v1/messages、Gemini :generateContent。 */
    const val CAP_CHAT = "chat"

    /** 向量能力：/v1/embeddings、Gemini :embedContent / :batchEmbedContents。 */
    const val CAP_EMBEDDINGS = "embeddings"

    /** 已知能力全集：管理端写渠道时按它校验——拼错的能力名等于悄悄关掉一条端点。 */
    /** 图片生成：/v1/images/generations。按次计价的模型（DALL·E）没有 usage，靠定价里的「按次」收费。 */
    const val CAP_IMAGES = "images"

    /** 重排序：/v1/rerank（Jina / Cohere 形状，OpenAI 兼容站常带）。usage 只有 total_tokens，按输入计。 */
    const val CAP_RERANK = "rerank"

    /** 音频：/v1/audio/speech（文本→音频）、/v1/audio/transcriptions、/v1/audio/translations（音频→文本）。 */
    const val CAP_AUDIO = "audio"

    val KNOWN_CAPABILITIES = setOf(CAP_CHAT, CAP_EMBEDDINGS, CAP_IMAGES, CAP_RERANK, CAP_AUDIO)

    /**
     * 入站端点 → 需要的上游能力。只看模型清单会把 embeddings 路由到只会 chat 的渠道，
     * 上游 404 之后还要走重试、结算回滚与一条 error 日志——在候选阶段就按能力过滤便宜得多。
     */
    fun capabilityFor(path: String): String = when {
        path == "/v1/embeddings" -> CAP_EMBEDDINGS
        path == "/v1/images/generations" -> CAP_IMAGES
        path == "/v1/rerank" -> CAP_RERANK
        path.startsWith("/v1/audio/") -> CAP_AUDIO
        // Gemini 原生面：/v1beta/models/{model}:{action}，能力由 action 决定
        path.startsWith("/v1beta/models/") ->
            if (path.contains(":embedContent") || path.contains(":batchEmbedContents")) CAP_EMBEDDINGS
            else CAP_CHAT
        else -> CAP_CHAT
    }

    /**
     * status=1 ∩ 未在 429 冷却期 ∩ 模型命中（models 清单或 modelMapping 键）∩ 分组交集 ∩ 能力命中。
     * nowMs 必传，避免漏传致冷却过滤失效。
     */
    fun candidates(
        channels: List<Channel>, model: String, group: String, nowMs: Long, capability: String = CAP_CHAT,
    ): List<Channel> =
        channels.filter { ch ->
            ch.status == 1 &&
                ch.cooldownUntil <= nowMs &&
                modelSupported(ch, model) &&
                ch.groups.split(',').map { it.trim() }.contains(group) &&
                capabilitySupported(ch, capability)
        }

    /**
     * 无候选原因。`retryable` = 客户端重试是否有意义：配置不变就永远不会可用的（模型没配、能力没声明、
     * 分组不通）必须回 4xx，回 503 只会让客户端按退避策略一直撞墙。
     */
    enum class Reason(val retryable: Boolean) {
        NO_CHANNEL_ENABLED(true),
        COOLDOWN(true),
        MODEL_NOT_LISTED(false),
        GROUP_NOT_SERVED(false),
        CAPABILITY_MISSING(false),
        UNKNOWN(true),
    }

    /**
     * 定位无候选卡在哪一环（只用于错误文案与日志，不参与路由决策）。
     * 按 [candidates] 同一组谓词从前往后逐层收紧，第一个变空的层就是原因。
     */
    fun noCandidateReason(
        channels: List<Channel>, model: String, group: String, nowMs: Long, capability: String,
    ): Reason {
        val enabled = channels.filter { it.status == 1 }
        if (enabled.isEmpty()) return Reason.NO_CHANNEL_ENABLED
        val cooled = enabled.filter { it.cooldownUntil <= nowMs }
        if (cooled.isEmpty()) return Reason.COOLDOWN
        val modeled = cooled.filter { modelSupported(it, model) }
        if (modeled.isEmpty()) return Reason.MODEL_NOT_LISTED
        val grouped = modeled.filter { ch -> ch.groups.split(',').map { it.trim() }.contains(group) }
        if (grouped.isEmpty()) return Reason.GROUP_NOT_SERVED
        if (grouped.none { capabilitySupported(it, capability) }) return Reason.CAPABILITY_MISSING
        return Reason.UNKNOWN
    }

    /**
     * 校验并归一管理端填的能力清单：去空白、去重、保持填写顺序；空串或未知能力名一律 400 拒绝。
     * 静默接受拼错的能力名，等于把该渠道从对应端点上悄悄摘掉，事后只能靠用户投诉发现。
     */
    fun requireCapabilities(csv: String): String {
        val items = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) rejectField("capabilities", GatewayErrorCodes.CHANNEL_CAPABILITIES_REQUIRED)
        if (items.any { it !in KNOWN_CAPABILITIES }) rejectField("capabilities", GatewayErrorCodes.CHANNEL_CAPABILITY_UNKNOWN)
        return items.distinct().joinToString(",")
    }

    private fun capabilitySupported(ch: Channel, capability: String): Boolean =
        ch.capabilities.split(',').map { it.trim() }.contains(capability)

    private fun modelSupported(ch: Channel, model: String): Boolean {
        if (ch.models.split(',').map { it.trim() }.contains(model)) return true
        // 映射键命中：{"请求名":"上游名"}——不做完整 JSON 解析，键名精确匹配即可
        val mapping = ch.modelMapping ?: return false
        return mapping.contains("\"$model\"")
    }

    fun pick(candidates: List<Channel>, excludeIds: Set<Long>, random: Random): Channel? {
        val pool = candidates.filter { it.id !in excludeIds }
        if (pool.isEmpty()) return null
        val topPriority = pool.maxOf { it.priority }
        val layer = pool.filter { it.priority == topPriority }
        val totalWeight = layer.sumOf { it.weight.coerceAtLeast(1) }
        var roll = random.nextInt(totalWeight)
        for (ch in layer) {
            roll -= ch.weight.coerceAtLeast(1)
            if (roll < 0) return ch
        }
        return layer.last()
    }
}
