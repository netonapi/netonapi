package controller.app.gateway

import kotlinx.serialization.Serializable
import neton.core.annotations.*

@Serializable
data class PublicEndpointVO(val path: String, val protocol: String, val capability: String)

@Serializable
data class PublicInfoVO(
    /** 网关对外地址（NEWGATE_PUBLIC_API_URL）；没配为 null，控制台据此提示用户去问管理员 */
    val apiBase: String? = null,
    val endpoints: List<PublicEndpointVO>,
)

/**
 * 控制台「接入方式」用的公开信息：网关地址与端点清单。匿名可读 —— 这些本来就是写在文档里的东西，
 * 不含任何租户数据。地址来自环境变量而不是请求的 Host：控制台经服务端代理打后端，后端看到的 Host
 * 是容器内部名，猜不出用户该填哪个域名。
 */
@Controller("/gateway/public-info")
@AllowAnonymous
class PublicInfoController {
    @Get("")
    suspend fun info(): PublicInfoVO = PublicInfoVO(
        apiBase = neton.core.config.getEnv(ENV_PUBLIC_API_URL)?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() },
        endpoints = listOf(
            PublicEndpointVO("/v1/chat/completions", "openai", "chat"),
            PublicEndpointVO("/v1/responses", "openai", "chat"),
            PublicEndpointVO("/v1/embeddings", "openai", "embeddings"),
            PublicEndpointVO("/v1/images/generations", "openai", "images"),
            PublicEndpointVO("/v1/rerank", "openai", "rerank"),
            PublicEndpointVO("/v1/audio/speech", "openai", "audio"),
            PublicEndpointVO("/v1/audio/transcriptions", "openai", "audio"),
            PublicEndpointVO("/v1/audio/translations", "openai", "audio"),
            PublicEndpointVO("/v1/models", "openai", "chat"),
            PublicEndpointVO("/v1/messages", "anthropic", "chat"),
            PublicEndpointVO("/v1beta/models/{model}:generateContent", "gemini", "chat"),
            PublicEndpointVO("/v1beta/models/{model}:streamGenerateContent", "gemini", "chat"),
            PublicEndpointVO("/v1beta/models/{model}:embedContent", "gemini", "embeddings"),
        ),
    )

    companion object {
        const val ENV_PUBLIC_API_URL = "NEWGATE_PUBLIC_API_URL"
    }
}
