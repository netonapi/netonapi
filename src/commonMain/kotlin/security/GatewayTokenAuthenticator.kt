package security

import logic.TokenLogic
import neton.core.component.NetonContext
import neton.core.interfaces.Authenticator
import neton.core.interfaces.Identity
import neton.core.interfaces.RequestContext

/**
 * gateway 组认证器：Authorization: Bearer sk-xxx → 哈希查表 → Identity。
 * TokenLogic 惰性解析：security 配置阶段早于 @Logic 装配，首次请求时再取。
 */
class GatewayTokenAuthenticator(private val ctx: NetonContext) : Authenticator {

    override val name: String = "gateway-token"

    private val tokenLogic: TokenLogic by lazy { ctx.get(TokenLogic::class) }

    override suspend fun authenticate(context: RequestContext): Identity? {
        val header = context.headers["Authorization"] ?: context.headers["authorization"] ?: return null
        val plaintext = header.removePrefix("Bearer ").trim()
        if (!plaintext.startsWith("sk-")) return null
        val token = tokenLogic.verify(plaintext) ?: return null
        return GatewayTokenIdentity(userId = token.userId, tokenId = token.id)
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
