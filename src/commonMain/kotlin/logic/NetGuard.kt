package logic

/**
 * 网络侧准入的纯函数：上游地址 SSRF 校验 + 令牌 IP 白名单。
 *
 * 两者都只依赖字符串输入，便于单测穷举边界。
 */
object NetGuard {

    // ===== SSRF：渠道 baseUrl / proxyUrl 校验 =====

    sealed interface UrlVerdict {
        data object Ok : UrlVerdict
        /** [code] 是 [GatewayErrorCodes] 的 UPSTREAM_URL_* 机器码（进响应）；[reason] 是给日志与测试看的细节。 */
        data class Rejected(val code: String, val reason: String) : UrlVerdict
    }

    /**
     * 主机名 → IP 字面量列表；空 = 解析不出来。
     *
     * 由调用方注入（生产 = 平台 getaddrinfo，单测 = 假解析器），NetGuard 因此保持纯函数、
     * 能在 commonTest 里穷举边界。实现通常是**阻塞**的：只用于管理员写入渠道这类低频路径，
     * 绝不可进中转热路径（每请求解析一次 = 把 DNS 变成延迟与故障源）。
     */
    fun interface HostResolver {
        fun resolve(host: String): List<String>
    }

    /**
     * 校验管理员配置的上游地址。
     *
     * 拒绝：非 http(s)、缺主机、以及指向**回环/内网/链路本地/元数据服务**的主机——
     * 否则网关会变成打内网的跳板（如 `169.254.169.254` 可取云厂商实例凭据）。
     *
     * [allowPrivate] 供自托管/内网部署显式放行（默认关闭）。
     *
     * [resolver] 非空时对**域名**再做一次解析复查：字面量看不出 `http://upstream.example`
     * 指向哪里，而它完全可以指向 169.254.169.254。多条 A 记录里混一个内网地址同样拒绝
     * （攻击者正是靠「多数时候返回公网地址」躲过一次性检查）；解析失败一律 fail-closed——
     * 把「DNS 挂了」当成「地址安全」是这类校验最典型的失效方式。
     * 注意这是**写入时**检查，挡不住之后改 DNS 记录的重绑定；那一层要靠部署侧出网过滤
     * （见 DEPLOY.md「SSRF」）。
     */
    fun checkUpstreamUrl(
        url: String,
        allowPrivate: Boolean = false,
        resolver: HostResolver? = null,
    ): UrlVerdict {
        val u = url.trim()
        if (u.isEmpty()) return UrlVerdict.Rejected(GatewayErrorCodes.UPSTREAM_URL_EMPTY, "url is empty")
        val scheme = u.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return UrlVerdict.Rejected(GatewayErrorCodes.UPSTREAM_URL_SCHEME_INVALID, "scheme must be http or https")
        val hostPort = u.removePrefix("$scheme://").substringBefore('/').substringBefore('?')
        if (hostPort.contains('@')) return UrlVerdict.Rejected(GatewayErrorCodes.UPSTREAM_URL_USERINFO_NOT_ALLOWED, "userinfo in URL is not allowed")
        val host = hostPort.substringBeforeLast(':').trim('[', ']').lowercase()
        if (host.isEmpty()) return UrlVerdict.Rejected(GatewayErrorCodes.UPSTREAM_URL_HOST_EMPTY, "host is empty")
        if (allowPrivate) return UrlVerdict.Ok
        if (isPrivateHost(host)) return UrlVerdict.Rejected(GatewayErrorCodes.UPSTREAM_URL_PRIVATE_ADDRESS, "host '$host' resolves to a private or link-local address")
        if (resolver != null && !isIpLiteral(host)) {
            val addresses = runCatching { resolver.resolve(host) }.getOrDefault(emptyList())
            if (addresses.isEmpty()) return UrlVerdict.Rejected(GatewayErrorCodes.UPSTREAM_URL_UNRESOLVABLE, "host '$host' could not be resolved")
            val internal = addresses.mapNotNull { normalizeIp(it) }.firstOrNull { isPrivateHost(it) }
            if (internal != null) {
                return UrlVerdict.Rejected(
                    GatewayErrorCodes.UPSTREAM_URL_PRIVATE_ADDRESS,
                    "host '$host' resolves to a private or link-local address ($internal)",
                )
            }
        }
        return UrlVerdict.Ok
    }

    /** host 是否已是 IP 字面量（含 IPv6）——是则无需 DNS 就能判定。 */
    fun isIpLiteral(host: String): Boolean = host.contains(':') || ipv4ToLong(host) != null

    /** 主机是否属于内网/回环/链路本地（含云元数据地址）。仅字面量判断，不做 DNS 解析。 */
    fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".internal") || host.endsWith(".local")) return true
        if (host == "::1" || host == "0:0:0:0:0:0:0:1") return true
        // IPv6 唯一本地地址 fc00::/7 与链路本地 fe80::/10
        if (host.contains(':')) {
            val p = host.lowercase()
            if (p.startsWith("fc") || p.startsWith("fd") || p.startsWith("fe8") ||
                p.startsWith("fe9") || p.startsWith("fea") || p.startsWith("feb")) return true
            return false
        }
        val o = host.split('.')
        if (o.size != 4 || o.any { it.isEmpty() || !it.all(Char::isDigit) }) return false  // 域名交由部署侧策略
        val b = o.map { it.toIntOrNull() ?: return false }
        if (b.any { it !in 0..255 }) return false
        return when {
            b[0] == 127 -> true                                   // 回环
            b[0] == 10 -> true                                    // 私有 A
            b[0] == 172 && b[1] in 16..31 -> true                 // 私有 B
            b[0] == 192 && b[1] == 168 -> true                    // 私有 C
            b[0] == 169 && b[1] == 254 -> true                    // 链路本地（含 169.254.169.254 元数据）
            b[0] == 0 -> true                                     // 0.0.0.0/8
            b[0] == 100 && b[1] in 64..127 -> true                // CGNAT 100.64/10
            else -> false
        }
    }

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

    /** 归一化地址字面量：`1.2.3.4:7080` → `1.2.3.4`，`[::1]:7080` → `::1`，裸 IPv6 原样保留。 */
    fun normalizeIp(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return null
            return s.substring(1, end).trim().takeIf { it.isNotEmpty() }
        }
        // 恰好一个冒号才可能是 host:port；IPv6 字面量含多个冒号，截断会毁掉地址
        if (s.count { it == ':' } == 1) return s.substringBefore(':').trim().takeIf { it.isNotEmpty() }
        return s
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
