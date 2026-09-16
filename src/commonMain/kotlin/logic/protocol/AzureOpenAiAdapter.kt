package logic.protocol

/**
 * Azure OpenAI：协议与 OpenAI 相同（请求/响应/流式/usage 全部复用 [OpenAiAdapter]，
 * 所以 id 仍是 "openai"，跨协议转码按 openai 走），只有两处不一样：
 *
 * 1. 鉴权头是 `api-key`，不是 `Authorization: Bearer`；
 * 2. 路径里带 deployment 与 api-version：`/openai/deployments/{deployment}/chat/completions?api-version=…`。
 *    deployment 取渠道 modelMapping 映射后的上游模型名，所以「gpt-4o → 我的部署名」在映射里配一次即可。
 */
object AzureOpenAiAdapter : ProtocolAdapter by OpenAiAdapter {
    const val DEFAULT_API_VERSION = "2024-10-21"

    override fun upstreamHeaders(apiKey: String) = mapOf(
        "api-key" to apiKey,
        "Content-Type" to "application/json",
    )

    fun path(inboundPath: String, deployment: String, apiVersion: String?): String {
        // Responses API 只在 Azure 的 v1 面上有，部署名走 body 的 model，路径不带 deployments
        if (inboundPath == "/v1/responses") return "/openai/v1/responses?api-version=preview"
        val op = when {
            inboundPath.endsWith("/embeddings") -> "/embeddings"
            inboundPath.endsWith("/images/generations") -> "/images/generations"
            inboundPath == "/v1/completions" -> "/completions"
            else -> "/chat/completions"
        }
        val v = apiVersion?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_API_VERSION
        return "/openai/deployments/$deployment$op?api-version=$v"
    }
}
