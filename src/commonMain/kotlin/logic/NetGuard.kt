package logic

import neton.http.client.HostResolver
import neton.http.client.HttpClientError
import neton.http.client.HttpClientException
import neton.http.client.OutboundAddressPolicy
import neton.http.client.TargetRejection

/**
 * 网络侧准入的纯函数：上游地址 SSRF 校验 + 令牌 IP 白名单。
 *
 * 两者都只依赖字符串输入，便于单测穷举边界。
 */
object NetGuard {

    // ===== SSRF：渠道 baseUrl / proxyUrl / 服务账号 token_uri 校验 =====

    sealed interface UrlVerdict {
        data object Ok : UrlVerdict
        /** [code] 是 [GatewayErrorCodes] 的 UPSTREAM_* 机器码（进响应）；[reason] 是给日志与测试看的细节。 */
        data class Rejected(val code: String, val reason: String) : UrlVerdict
    }

    /** 被校验的地址是什么：决定按目标规则（scheme + 端口 + 地址）还是代理规则（地址，端口不限）校验。 */
    enum class UrlRole {
        /** 渠道 baseUrl：http / https。 */
        UPSTREAM,
        /** 服务账号 JSON 里的 token_uri：只允许 https（里面带签名断言）。 */
        TOKEN_ENDPOINT,
        /** 渠道 proxyUrl：代理端点本身。 */
        PROXY,
    }

    /**
     * 写入时校验管理员配置的上游地址——**快速反馈**，不是强制点。强制点在请求时：渠道转发、探测、拉模型、
     * Vertex 换 token 全部走 [RelayEngine.guardedClients] 建的受控客户端，每条连接解析一次、全部地址校验、
     * 只连校验过的地址（DNS 重绑定、写库绕过本校验的渠道都挡在连接前）。
     *
     * 两处用的是**同一个** [policy]（运维出站策略 `[egress]`，见 neton-http `EgressSettings`）与同一个解析器，
     * 所以这里通过的地址请求时也通过（除非 DNS 之后变了）。内网上游（自托管模型在 10.x）只能由运维在
     * 部署配置里放行，后台改不了。
     *
     * 先做语法检查（空、scheme、userinfo、主机、端口），再交给 [policy]：目标按 `admit`（scheme / 端口 / 地址），
     * 代理按 `admitProxy`（地址，端口不限）。解析失败 fail-closed。
     */
    suspend fun checkUpstreamUrl(
        url: String,
        policy: OutboundAddressPolicy,
        resolver: HostResolver,
        role: UrlRole = UrlRole.UPSTREAM,
    ): UrlVerdict {
        val u = url.trim()
        if (u.isEmpty()) return UrlVerdict.Rejected(GatewayErrorCodes.UPSTREAM_URL_EMPTY, "url is empty")
        val scheme = u.substringBefore("://", "").lowercase()
        val schemeOk = when (role) {
            UrlRole.TOKEN_ENDPOINT -> scheme == "https"
            else -> scheme == "http" || scheme == "https"
        }
        if (!schemeOk) return UrlVerdict.Rejected(GatewayErrorCodes.UPSTREAM_URL_SCHEME_INVALID, "scheme '$scheme' is not allowed for $role")
        val hostPort = u.substring(scheme.length + 3).substringBefore('/').substringBefore('?').substringBefore('#')
        if (hostPort.contains('@')) return UrlVerdict.Rejected(GatewayErrorCodes.UPSTREAM_URL_USERINFO_NOT_ALLOWED, "userinfo in URL is not allowed")
        val host: String
        val portText: String?
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return UrlVerdict.Rejected(GatewayErrorCodes.UPSTREAM_URL_HOST_EMPTY, "unterminated IPv6 literal")
            host = hostPort.substring(1, close)
            portText = hostPort.substring(close + 1).takeIf { it.isNotEmpty() }?.let {
                if (!it.startsWith(':')) return UrlVerdict.Rejected(GatewayErrorCodes.UPSTREAM_URL_HOST_EMPTY, "garbage after IPv6 literal")
                it.substring(1)
            }
        } else {
            host = hostPort.substringBefore(':')
            portText = if (':' in hostPort) hostPort.substringAfter(':') else null
        }
        if (host.isEmpty()) return UrlVerdict.Rejected(GatewayErrorCodes.UPSTREAM_URL_HOST_EMPTY, "host is empty")
        val port = when (portText) {
            null -> if (scheme == "https") 443 else 80
            else -> portText.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: return UrlVerdict.Rejected(GatewayErrorCodes.UPSTREAM_URL_PORT_FORBIDDEN, "port '$portText' is not a port")
        }
        return try {
            if (role == UrlRole.PROXY) policy.admitProxy(host, port, resolver) else policy.admit(scheme, host, port, resolver)
            UrlVerdict.Ok
        } catch (e: HttpClientException) {
            val rejected = e.error as? HttpClientError.TargetRejected ?: throw e
            UrlVerdict.Rejected(rejectionCode(rejected.reason), rejected.message)
        }
    }

    /** 受控客户端拒绝目标的原因 → 机器码（写入时与请求时同一套码）。 */
    fun rejectionCode(reason: TargetRejection): String = when (reason) {
        TargetRejection.SCHEME -> GatewayErrorCodes.UPSTREAM_URL_SCHEME_INVALID
        TargetRejection.PORT -> GatewayErrorCodes.UPSTREAM_URL_PORT_FORBIDDEN
        TargetRejection.ADDRESS -> GatewayErrorCodes.UPSTREAM_URL_PRIVATE_ADDRESS
        TargetRejection.RESOLVE -> GatewayErrorCodes.UPSTREAM_URL_UNRESOLVABLE
        TargetRejection.PROXY -> GatewayErrorCodes.UPSTREAM_PROXY_FORBIDDEN
    }

    /** [error] 是受控客户端拒绝目标时的机器码，否则 null（网络错误、超时、HTTP 状态另行处理）。 */
    fun rejectionCode(error: HttpClientError): String? =
        (error as? HttpClientError.TargetRejected)?.let { rejectionCode(it.reason) }

    // ===== 客户端 IP =====

    /**
     * 旧的网关专用可信代理变量。来源解析已统一到框架（`ratelimit.trusted_proxies`），
     * 这个变量不再生效；仍配着它说明部署还按旧方式信任代理，启动时直接拒绝，见 [checkLegacyTrustedProxies]。
     */
    const val ENV_TRUSTED_PROXIES = "NEWGATE_TRUSTED_PROXIES"

    /**
     * 配了旧变量就拒绝启动：静默忽略的话，反代后面所有请求的来源都变成反代自己，
     * 配了 IP 白名单的令牌会全部 403 —— 而运营者以为白名单照常工作。
     */
    fun checkLegacyTrustedProxies(value: String?) {
        if (!value.isNullOrBlank()) {
            error(
                "$ENV_TRUSTED_PROXIES is no longer supported; set the trusted proxies once for the whole app " +
                    "via NETON_ROUTING__RATELIMIT__TRUSTED_PROXIES (routing.conf [ratelimit] trusted_proxies) and unset it"
            )
        }
    }

    // ===== 令牌 IP 白名单 =====

    /**
     * [allowed] 为逗号分隔的精确 IP 或 CIDR（如 `1.2.3.4, 10.0.0.0/8`）；空表示不限。
     * 客户端 IP 无法确定时（[clientIp] 为 null）视为不通过——白名单必须是「明确允许」而非「默认放行」。
     */
    fun ipAllowed(allowed: String, clientIp: String?): Boolean {
        val rules = allowed.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (rules.isEmpty()) return true
        val ip = clientIp?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return rules.any { matches(it, ip) }
    }

    private fun matches(rule: String, ip: String): Boolean {
        if (!rule.contains('/')) {
            // 精确规则必须本身是合法地址：写错的规则（如 1.2.3.256）不得因字面相等而放行
            val validExact = ipv4ToLong(rule) != null || rule.contains(':')
            return validExact && rule.equals(ip, ignoreCase = true)
        }
        val prefix = rule.substringBefore('/')
        val bits = rule.substringAfter('/').toIntOrNull() ?: return false
        val a = ipv4ToLong(prefix) ?: return false
        val b = ipv4ToLong(ip) ?: return false
        if (bits !in 0..32) return false
        if (bits == 0) return true
        val mask = (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
        return (a and mask) == (b and mask)
    }

    private fun ipv4ToLong(ip: String): Long? {
        val p = ip.split('.')
        if (p.size != 4) return null
        var v = 0L
        for (part in p) {
            val n = part.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }
}
