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
        data class Rejected(val reason: String) : UrlVerdict
    }

    /**
     * 校验管理员配置的上游地址。
     *
     * 拒绝：非 http(s)、缺主机、以及指向**回环/内网/链路本地/元数据服务**的主机——
     * 否则网关会变成打内网的跳板（如 `169.254.169.254` 可取云厂商实例凭据）。
     *
     * [allowPrivate] 供自托管/内网部署显式放行（默认关闭）。
     */
    fun checkUpstreamUrl(url: String, allowPrivate: Boolean = false): UrlVerdict {
        val u = url.trim()
        if (u.isEmpty()) return UrlVerdict.Rejected("url is empty")
        val scheme = u.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return UrlVerdict.Rejected("scheme must be http or https")
        val hostPort = u.removePrefix("$scheme://").substringBefore('/').substringBefore('?')
        if (hostPort.contains('@')) return UrlVerdict.Rejected("userinfo in URL is not allowed")
        val host = hostPort.substringBeforeLast(':').trim('[', ']').lowercase()
        if (host.isEmpty()) return UrlVerdict.Rejected("host is empty")
        if (allowPrivate) return UrlVerdict.Ok
        if (isPrivateHost(host)) return UrlVerdict.Rejected("host '$host' resolves to a private or link-local address")
        return UrlVerdict.Ok
    }

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
