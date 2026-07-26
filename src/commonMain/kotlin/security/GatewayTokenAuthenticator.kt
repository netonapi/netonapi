package security

import logic.TokenLogic
import neton.core.component.NetonContext
import neton.core.interfaces.Authenticator
import neton.core.interfaces.Identity
import neton.core.interfaces.RequestContext

/**
 * gateway 组认证器：把三家客户端的原生认证载体统一映射到 NewGate 令牌。
 *
 * 支持 `Authorization: Bearer sk-`（OpenAI）、`x-api-key`（Anthropic）、
 * `x-goog-api-key` 与 `?key=`（Gemini）——官方 SDK 只会按各自协议发认证头，
 * 只认 Bearer 会逼用户改配置甚至根本接不进来。
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
        val q = context.path.substringAfter('?', "")
        if (q.isEmpty()) return null
        return q.split('&')
            .firstOrNull { it.startsWith("key=") }
            ?.removePrefix("key=")
            ?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val KEY_PREFIX = "sk-"
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
