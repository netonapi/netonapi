package logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetGuardTest {

    private fun ok(url: String) = NetGuard.checkUpstreamUrl(url) is NetGuard.UrlVerdict.Ok
    private fun rejected(url: String) = NetGuard.checkUpstreamUrl(url) is NetGuard.UrlVerdict.Rejected

    // ===== SSRF =====

    @Test fun accepts_public_https_upstreams() {
        assertTrue(ok("https://api.openai.com/v1"))
        assertTrue(ok("https://api.anthropic.com"))
        assertTrue(ok("http://8.8.8.8:8080/v1"))
    }

    @Test fun rejects_cloud_metadata_endpoint() {
        // 最危险的一个：可取云厂商实例凭据
        assertTrue(rejected("http://169.254.169.254/latest/meta-data/"))
    }

    @Test fun rejects_loopback_and_private_ranges() {
        for (u in listOf(
            "http://127.0.0.1:8080", "http://localhost:3000", "https://0.0.0.0",
            "http://10.1.2.3", "http://172.16.5.4", "http://172.31.255.1",
            "http://192.168.1.1", "http://100.64.0.1",
        )) assertTrue(rejected(u), "应拒绝内网地址：$u")
    }

    @Test fun allows_private_ranges_when_explicitly_enabled() {
        // 自托管/内网部署可显式放行
        assertTrue(NetGuard.checkUpstreamUrl("http://10.1.2.3", allowPrivate = true) is NetGuard.UrlVerdict.Ok)
    }

    @Test fun public_side_of_172_is_not_private() {
        // 172.16/12 之外的 172.x 是公网，不应误伤
        assertTrue(ok("http://172.15.0.1"))
        assertTrue(ok("http://172.32.0.1"))
    }

    @Test fun rejects_non_http_schemes_and_userinfo() {
        assertTrue(rejected("file:///etc/passwd"))
        assertTrue(rejected("gopher://evil.com"))
        assertTrue(rejected("ftp://x.com"))
        assertTrue(rejected(""))
        // user@host 可用于混淆真实主机
        assertTrue(rejected("http://api.openai.com@127.0.0.1/v1"))
    }

    @Test fun rejects_ipv6_loopback_and_local() {
        assertTrue(rejected("http://[::1]:8080"))
        assertTrue(rejected("http://[fd00::1]"))
        assertTrue(rejected("http://[fe80::1]"))
    }

    // ===== IP 白名单 =====

    @Test fun empty_whitelist_allows_everything() {
        assertTrue(NetGuard.ipAllowed("", "1.2.3.4"))
        assertTrue(NetGuard.ipAllowed("   ", null))
    }

    @Test fun exact_ip_matches() {
        assertTrue(NetGuard.ipAllowed("1.2.3.4", "1.2.3.4"))
        assertFalse(NetGuard.ipAllowed("1.2.3.4", "1.2.3.5"))
    }

    @Test fun cidr_matches() {
        assertTrue(NetGuard.ipAllowed("10.0.0.0/8", "10.255.1.2"))
        assertFalse(NetGuard.ipAllowed("10.0.0.0/8", "11.0.0.1"))
        assertTrue(NetGuard.ipAllowed("192.168.1.0/24", "192.168.1.99"))
        assertFalse(NetGuard.ipAllowed("192.168.1.0/24", "192.168.2.1"))
        assertTrue(NetGuard.ipAllowed("0.0.0.0/0", "8.8.8.8"))
    }

    @Test fun multiple_rules_are_any_match() {
        assertTrue(NetGuard.ipAllowed("1.2.3.4, 10.0.0.0/8", "10.1.1.1"))
        assertFalse(NetGuard.ipAllowed("1.2.3.4, 10.0.0.0/8", "9.9.9.9"))
    }

    @Test fun unknown_client_ip_is_denied_when_whitelist_set() {
        // 白名单必须是「明确允许」；取不到客户端 IP 时不能默认放行
        assertFalse(NetGuard.ipAllowed("1.2.3.4", null))
        assertFalse(NetGuard.ipAllowed("1.2.3.4", ""))
    }

    @Test fun malformed_rules_do_not_accidentally_allow() {
        assertFalse(NetGuard.ipAllowed("not-an-ip/8", "1.2.3.4"))
        assertFalse(NetGuard.ipAllowed("10.0.0.0/99", "10.0.0.1"))
        assertFalse(NetGuard.ipAllowed("1.2.3.256", "1.2.3.256"))
    }

    // ===== 旧可信代理变量 =====

    @Test fun legacy_trusted_proxies_variable_refuses_to_start() {
        assertFailsWith<IllegalStateException> { NetGuard.checkLegacyTrustedProxies("127.0.0.1,::1") }
    }

    @Test fun unset_legacy_variable_is_fine() {
        NetGuard.checkLegacyTrustedProxies(null)
        NetGuard.checkLegacyTrustedProxies("  ")
    }

    @Test fun normalize_ip_strips_port_and_brackets_without_mangling_ipv6() {
        assertEquals("127.0.0.1", NetGuard.normalizeIp("127.0.0.1:7080"))
        assertEquals("::1", NetGuard.normalizeIp("[::1]:7080"))
        assertEquals("2001:db8::1", NetGuard.normalizeIp("2001:db8::1"))
        assertNull(NetGuard.normalizeIp("   "))
        assertNull(NetGuard.normalizeIp("[::1"))
    }

    // ===== SSRF：域名解析复查 =====

    /** 假解析器：记录被查过的主机，按表返回地址（表里没有 = 解析失败）。 */
    private class FakeResolver(private val table: Map<String, List<String>>) : NetGuard.HostResolver {
        val queried = mutableListOf<String>()
        override fun resolve(host: String): List<String> {
            queried += host
            return table[host] ?: emptyList()
        }
    }

    private fun rejectReason(v: NetGuard.UrlVerdict): String =
        (v as? NetGuard.UrlVerdict.Rejected)?.reason ?: error("应被拒绝，实际 $v")

    @Test fun rejects_domain_that_resolves_to_an_internal_address() {
        // 多条 A 记录里混一个元数据地址也必须拒绝：攻击者靠「多数时候返回公网地址」躲过一次性检查
        val r = FakeResolver(mapOf("upstream.example" to listOf("93.184.216.34", "169.254.169.254")))
        val v = NetGuard.checkUpstreamUrl("https://upstream.example/v1", resolver = r)
        assertTrue(v is NetGuard.UrlVerdict.Rejected, "应拒绝：$v")
        assertTrue(rejectReason(v).contains("169.254.169.254"), "拒绝原因要点名内网地址：${rejectReason(v)}")
        assertEquals(GatewayErrorCodes.UPSTREAM_URL_PRIVATE_ADDRESS, (v as NetGuard.UrlVerdict.Rejected).code)
        assertEquals(listOf("upstream.example"), r.queried)
    }

    @Test fun rejects_domains_pointing_at_loopback_private_or_ipv6_local() {
        for ((host, ip) in listOf(
            "a.example" to "127.0.0.1", "b.example" to "10.0.0.5",
            "c.example" to "192.168.1.1", "d.example" to "::1", "e.example" to "fd00::1",
        )) {
            val v = NetGuard.checkUpstreamUrl("http://$host", resolver = FakeResolver(mapOf(host to listOf(ip))))
            assertTrue(v is NetGuard.UrlVerdict.Rejected, "$host → $ip 应被拒绝：$v")
        }
    }

    @Test fun accepts_domain_that_resolves_to_public_addresses() {
        val r = FakeResolver(mapOf("api.example" to listOf("93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946")))
        assertTrue(NetGuard.checkUpstreamUrl("https://api.example/v1", resolver = r) is NetGuard.UrlVerdict.Ok)
    }

    @Test fun unresolvable_host_is_rejected_not_waved_through() {
        // fail-closed：把「DNS 挂了」当成「地址安全」是这类校验最典型的失效方式
        val v = NetGuard.checkUpstreamUrl("https://no-such-host.example", resolver = FakeResolver(emptyMap()))
        assertTrue(rejectReason(v).contains("could not be resolved"), rejectReason(v))
    }

    @Test fun resolver_exception_is_rejected_not_waved_through() {
        val boom = NetGuard.HostResolver { throw RuntimeException("dns boom") }
        assertTrue(NetGuard.checkUpstreamUrl("https://api.example", resolver = boom) is NetGuard.UrlVerdict.Rejected)
    }

    @Test fun resolver_is_not_consulted_for_literals_or_when_private_is_allowed() {
        val r = FakeResolver(mapOf("10.0.0.1" to listOf("8.8.8.8")))
        assertTrue(NetGuard.checkUpstreamUrl("http://10.0.0.1", resolver = r) is NetGuard.UrlVerdict.Rejected)
        assertTrue(r.queried.isEmpty(), "IP 字面量不该走 DNS")
        val r2 = FakeResolver(emptyMap())
        assertTrue(NetGuard.checkUpstreamUrl("http://10.0.0.1", allowPrivate = true, resolver = r2) is NetGuard.UrlVerdict.Ok)
        assertTrue(r2.queried.isEmpty(), "显式放行内网时不该被 DNS 复查拦下")
        assertTrue(NetGuard.isIpLiteral("[::1]"))
        assertFalse(NetGuard.isIpLiteral("upstream.example"))
    }

    @Test fun addresses_from_the_resolver_are_normalized_before_the_check() {
        // getaddrinfo 可能给出带方括号的形式，归一化之后才判得出私网
        val r = FakeResolver(mapOf("v6.example" to listOf("[fd00::1]")))
        assertTrue(NetGuard.checkUpstreamUrl("http://v6.example", resolver = r) is NetGuard.UrlVerdict.Rejected)
    }

    @Test fun platform_resolver_returns_numeric_literals_that_read_as_internal() {
        // 真解析器（posix = getaddrinfo；没有实现的平台返回 null，此处直接跳过）。
        // 这条用例的价值在于覆盖 cinterop 本身：getaddrinfo → getnameinfo(NI_NUMERICHOST) 必须
        // 吐出我们能解析的数字字面量，且 localhost 的每个地址都被判为内网/回环。
        //
        // 故意不断言「NXDOMAIN → fail-closed」：解析器行为依赖宿主机 DNS，不可复现——例如
        // fake-IP 模式的本地代理会对 .invalid 也返回 198.18.0.0/15 这种保留但非内网的地址。
        // 解析不出来时的 fail-closed 由假解析器用例 unresolvable_host_is_rejected_not_waved_through 覆盖。
        val resolver = platformHostResolver() ?: return
        val ips = resolver.resolve("localhost")
        assertTrue(ips.isNotEmpty(), "localhost 应能解析（/etc/hosts）")
        for (raw in ips) {
            val ip = NetGuard.normalizeIp(raw) ?: raw
            assertTrue(NetGuard.isIpLiteral(ip), "解析结果必须是数字字面量：$raw")
            assertTrue(NetGuard.isPrivateHost(ip), "localhost 的地址必须判为内网/回环：$raw")
        }
    }
}
