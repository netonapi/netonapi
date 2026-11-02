package security

import kotlinx.serialization.json.JsonPrimitive
import logic.TokenLogic
import neton.core.component.NetonContext
import neton.core.http.RawResponseException
import neton.core.interfaces.Authenticator
import neton.core.interfaces.Identity
import neton.core.interfaces.RequestContext

/**
 * gateway 组认证器：把三家客户端的原生认证载体统一映射到 NetonAPI 令牌。
 *
 * 支持 `Authorization: Bearer sk-`（OpenAI）、`x-api-key`（Anthropic）、
 * `x-goog-api-key` 与 `?key=`（Gemini）——官方 SDK 只会按各自协议发认证头，
 * 只认 Bearer 会逼用户改配置甚至根本接不进来。
 *
 * 认证失败按入站协议回各家官方格式的 401（见 [unauthenticatedResponse]），而不是框架信封：
 * 官方 SDK 只认自家的错误结构，读不懂 `{code,message}`。
 *
 * TokenLogic 惰性解析：security 配置阶段早于 @Logic 装配，首次请求时再取。
 */
class GatewayTokenAuthenticator(private val ctx: NetonContext) : Authenticator {

    override val name: String = "gateway-token"

    private val tokenLogic: TokenLogic by lazy { ctx.get(TokenLogic::class) }

    override suspend fun authenticate(context: RequestContext): Identity? {
        val plaintext = extractKey(context) ?: return null
        if (!plaintext.startsWith(KEY_PREFIX)) return null
        val token = tokenLogic.verify(plaintext) ?: return null
        return GatewayTokenIdentity(userId = token.userId, tokenId = token.id)
    }

    /** 依次尝试三家协议的认证载体；查询串上的 key 放最后（最不推荐，会进日志/历史）。 */
    private fun extractKey(context: RequestContext): String? {
        header(context, "Authorization")?.let { return it.removePrefix("Bearer ").removePrefix("bearer ").trim() }
        header(context, "x-api-key")?.let { return it.trim() }          // Anthropic
        header(context, "x-goog-api-key")?.let { return it.trim() }      // Gemini
        return queryKey(context)                                          // Gemini ?key=
    }

    private fun header(context: RequestContext, name: String): String? =
        (context.headers[name] ?: context.headers[name.lowercase()])?.takeIf { it.isNotBlank() }

    private fun queryKey(context: RequestContext): String? {
        val q = context.query
        if (q.isEmpty()) return null
        return q.split('&')
            .firstOrNull { it.startsWith("key=") }
            ?.removePrefix("key=")
            ?.takeIf { it.isNotBlank() }
    }

    override fun unauthenticatedResponse(context: RequestContext): RawResponseException {
        val message = JsonPrimitive(if (extractKey(context) == null) MISSING_KEY else INVALID_KEY).toString()
        val body = when {
            context.path.startsWith("/v1beta/") ->
                """{"error":{"code":401,"message":$message,"status":"UNAUTHENTICATED"}}"""
            context.path.startsWith("/v1/messages") ->
                """{"type":"error","error":{"type":"authentication_error","message":$message}}"""
            else ->
                """{"error":{"message":$message,"type":"invalid_request_error","param":null,"code":"invalid_api_key"}}"""
        }
        return RawResponseException(401, body)
    }

    private companion object {
        const val KEY_PREFIX = "sk-"
        const val MISSING_KEY = "missing API key"
        const val INVALID_KEY = "invalid API key"
    }
}

/** 网关令牌身份：id = member userId；tokenId 供计费/日志关联。 */
class GatewayTokenIdentity(
    userId: Long,
    val tokenId: Long,
) : Identity {
    override val id: String = userId.toString()
    override val roles: Set<String> = emptySet()
    override val permissions: Set<String> = emptySet()
}
