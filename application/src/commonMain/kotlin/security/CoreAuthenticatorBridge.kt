package security

import neton.core.interfaces.Identity
import neton.security.Authenticator as SecurityAuthenticator
import neton.core.interfaces.Authenticator as CoreAuthenticator
import neton.core.interfaces.RequestContext as CoreRequestContext
import neton.security.RequestContext as SecurityRequestContext
import neton.security.identity.AuthenticationException

/**
 * Bridge：把一个 [neton.security.Authenticator] 包成 [neton.core.interfaces.Authenticator]。
 *
 * 镜像 [neton.security.SecurityModule] 里的 `JwtAuthenticatorAdapter` 模式（spec
 * Phase A 注释说明：core 与 security 是两个独立的 RequestContext / Authenticator
 * 接口体系，现网由 adapter 在 SecurityBuilder 注册口做适配）。
 *
 * 功能：
 * 1. 把 core RequestContext 映射成 security RequestContext（path / method / headers /
 *    routeGroup 直接转；query / body / session / remote 在 application HTTP 通道上
 *    用不到，统一返 null/empty）
 * 2. 捕获 [AuthenticationException]（legacy `JwtAuthenticator` 在 token 错误时抛
 *    这种异常）→ 返回 null（按 [Authenticator] 契约：凭据无效用 null 表达）
 *
 * Phase A 默认行为下，本 adapter 包的是 [neton.security.jwt.DispatchingJwtAuthenticator]，
 * 后者本身永远不抛 [AuthenticationException]（dispatcher 只 init 时抛
 * IllegalArgumentException 表示 misconfiguration）；catch 这条仍然保留以兼容下层
 * legacy `JwtAuthenticator` 的"token 错抛异常"老行为。
 */
public class CoreAuthenticatorBridge(
    private val delegate: SecurityAuthenticator,
    private val headerName: String = "Authorization",
    private val tokenPrefix: String = "Bearer ",
    private val sessionCookieName: String = "neton_session",
) : CoreAuthenticator {

    override val name: String = delegate.name

    override suspend fun authenticate(context: CoreRequestContext): Identity? {
        val sec = object : SecurityRequestContext {
            override val path = context.path
            override val method = context.method
            override val headers = withCookieSession(context.headers)
            override val routeGroup = context.routeGroup
            override fun getQueryParameter(name: String): String? = null
            override fun getQueryParameters(): Map<String, List<String>> = emptyMap()
            override suspend fun getBodyAsString(): String? = null
            override fun getSessionId(): String? = null
            override fun getRemoteAddress(): String? = null
        }
        return try {
            delegate.authenticate(sec)
        } catch (_: AuthenticationException) {
            null
        }
    }

    /**
     * 没有 `Authorization` 头但带了会话 Cookie 时，把 Cookie 里的 token 补成那个头。
     *
     * 后台是纯前端应用，token 存 localStorage 意味着一次 XSS 就能带走整个会话；
     * 改用 HttpOnly Cookie 后脚本读不到，但浏览器只会把它放在 `Cookie` 头里。
     *
     * 补在这里而不是改框架的 [neton.security.jwt.JwtAuthenticator]：后者按
     * Neton-JWT-Authenticator-Spec-v1 冻结成「只读某个请求头」，凭据放哪儿是应用层的
     * 传输选择，不该反向去改框架契约。放在 bridge 上还有个好处 —— legacy 与 unified
     * 两条验证链都在 delegate 之下，两边同时生效。
     *
     * 显式的 `Authorization` 头**优先**：移动端、服务间调用和 curl 的行为完全不变。
     */
    private fun withCookieSession(headers: Map<String, String>): Map<String, String> {
        if (headers.keys.any { it.equals(headerName, ignoreCase = true) }) return headers
        val cookieHeader = headers.entries.firstOrNull { it.key.equals("Cookie", ignoreCase = true) }?.value
        val token = readCookie(cookieHeader, sessionCookieName) ?: return headers
        return headers + (headerName to "$tokenPrefix$token")
    }

    private fun readCookie(cookieHeader: String?, name: String): String? {
        if (cookieHeader.isNullOrBlank()) return null
        for (part in cookieHeader.split(';')) {
            val pair = part.trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (pair.substring(0, eq).trim() != name) continue
            return pair.substring(eq + 1).trim().takeIf { it.isNotEmpty() }
        }
        return null
    }
}
