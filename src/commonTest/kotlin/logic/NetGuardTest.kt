package logic

import kotlinx.coroutines.runBlocking
import neton.http.client.HostResolver
import neton.http.client.IpAddress
import neton.http.client.IpNetwork
import neton.http.client.OutboundAddressPolicy
import neton.http.client.TargetRejection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetGuardTest {

    /** 假解析器：记录被查过的主机，按表返回地址（表里没有 = 解析失败）。 */
    private class FakeResolver(private val table: Map<String, List<String>> = emptyMap()) : HostResolver {
        val queried = mutableListOf<String>()
        override suspend fun resolve(host: String): List<IpAddress> {
            queried += host
            return (table[host] ?: throw IllegalStateException("NXDOMAIN $host")).map { IpAddress.parse(it)!! }
        }
    }

    private val strict = OutboundAddressPolicy()
    private val publicDns = FakeResolver(mapOf(
        "api.openai.com" to listOf("104.18.6.192"),
        "api.anthropic.com" to listOf("160.79.104.10"),
        "oauth2.googleapis.com" to listOf("142.250.72.10"),
        "proxy.example" to listOf("93.184.216.34"),
    ))

    private fun check(url: String, policy: OutboundAddressPolicy = strict, resolver: HostResolver = publicDns, role: NetGuard.UrlRole = NetGuard.UrlRole.UPSTREAM) =
        runBlocking { NetGuard.checkUpstreamUrl(url, policy, resolver, role) }

    private fun code(url: String, policy: OutboundAddressPolicy = strict, resolver: HostResolver = publicDns, role: NetGuard.UrlRole = NetGuard.UrlRole.UPSTREAM): String? =
        (check(url, policy, resolver, role) as? NetGuard.UrlVerdict.Rejected)?.code

    // ===== SSRF（写入时，与请求时同一个出站策略）=====

    @Test fun accepts_public_upstreams_on_allowed_ports() {
        assertNull(code("https://api.openai.com/v1"))
        assertNull(code("https://api.anthropic.com"))
        assertNull(code("http://8.8.8.8/v1"))
    }

    @Test fun rejects_cloud_metadata_endpoint() {
        // 最危险的一个：可取云厂商实例凭据
        assertEquals(GatewayErrorCodes.UPSTREAM_URL_PRIVATE_ADDRESS, code("http://169.254.169.254/latest/meta-data/"))
    }

    @Test fun rejects_loopback_private_and_special_ranges() {
        for (u in listOf(
            "http://127.0.0.1", "https://0.0.0.0", "http://10.1.2.3", "http://172.16.5.4", "http://172.31.255.1",
            "http://192.168.1.1", "http://100.64.0.1", "http://[::1]", "http://[fd00::1]", "http://[fe80::1]",
            "http://[::ffff:127.0.0.1]", "http://2130706433", "http://0x7f.1", "http://127.1",
        )) assertEquals(GatewayErrorCodes.UPSTREAM_URL_PRIVATE_ADDRESS, code(u), "应拒绝内网地址：$u")
    }

    @Test fun public_side_of_172_is_not_private() {
        assertNull(code("http://172.15.0.1"))
        assertNull(code("http://172.32.0.1"))
    }

    @Test fun ports_follow_the_egress_policy() {
        assertEquals(GatewayErrorCodes.UPSTREAM_URL_PORT_FORBIDDEN, code("http://8.8.8.8:8080/v1"))
        assertEquals(GatewayErrorCodes.UPSTREAM_URL_PORT_FORBIDDEN, code("http://8.8.8.8:99999/v1"))
        assertNull(code("http://8.8.8.8:8080/v1", OutboundAddressPolicy(allowedPorts = setOf(80, 443, 8080))))
    }

    @Test fun rejects_non_http_schemes_userinfo_and_empty() {
        assertEquals(GatewayErrorCodes.UPSTREAM_URL_SCHEME_INVALID, code("file:///etc/passwd"))
        assertEquals(GatewayErrorCodes.UPSTREAM_URL_SCHEME_INVALID, code("gopher://evil.com"))
        assertEquals(GatewayErrorCodes.UPSTREAM_URL_EMPTY, code(""))
        // user@host 可用于混淆真实主机
        assertEquals(GatewayErrorCodes.UPSTREAM_URL_USERINFO_NOT_ALLOWED, code("http://api.openai.com@127.0.0.1/v1"))
        assertEquals(GatewayErrorCodes.UPSTREAM_URL_HOST_EMPTY, code("http:///v1"))
    }

    @Test fun domains_are_resolved_and_every_answer_checked() {
        // 多条 A 记录里混一个元数据地址也必须拒绝
        val r = FakeResolver(mapOf("upstream.example" to listOf("93.184.216.34", "169.254.169.254")))
        val v = check("https://upstream.example/v1", resolver = r)
        assertEquals(GatewayErrorCodes.UPSTREAM_URL_PRIVATE_ADDRESS, (v as NetGuard.UrlVerdict.Rejected).code)
        assertTrue(v.reason.contains("169.254.169.254"), v.reason)
        assertEquals(listOf("upstream.example"), r.queried)
        for ((host, ip) in listOf("a.example" to "127.0.0.1", "b.example" to "10.0.0.5", "d.example" to "::1", "e.example" to "fd00::1")) {
            assertEquals(GatewayErrorCodes.UPSTREAM_URL_PRIVATE_ADDRESS, code("http://$host", resolver = FakeResolver(mapOf(host to listOf(ip)))), host)
        }
    }

    @Test fun unresolvable_host_is_rejected_not_waved_through() {
        // fail-closed：把「DNS 挂了」当成「地址安全」是这类校验最典型的失效方式
        assertEquals(GatewayErrorCodes.UPSTREAM_URL_UNRESOLVABLE, code("https://no-such-host.example", resolver = FakeResolver()))
    }

    @Test fun private_upstreams_need_the_operator_allowlist() {
        // 自托管模型在 10.x：只有部署配置 [egress] 放行的网段 / 主机能过，别的内网地址照样拒绝
        val selfHosted = OutboundAddressPolicy(allowedPorts = setOf(80, 443, 8000), allowedNetworks = listOf(IpNetwork.parse("10.20.0.0/16")))
        assertNull(code("http://10.20.3.4:8000/v1", selfHosted))
        assertEquals(GatewayErrorCodes.UPSTREAM_URL_PRIVATE_ADDRESS, code("http://10.30.0.1:8000/v1", selfHosted))
        assertEquals(GatewayErrorCodes.UPSTREAM_URL_PRIVATE_ADDRESS, code("http://169.254.169.254/", selfHosted))
        val byName = OutboundAddressPolicy(allowedHosts = setOf("vllm.internal"))
        assertNull(code("http://vllm.internal:8000/v1", byName, FakeResolver(mapOf("vllm.internal" to listOf("10.9.9.9")))))
    }

    @Test fun proxy_endpoints_are_checked_as_proxies() {
        assertNull(code("http://proxy.example:3128", role = NetGuard.UrlRole.PROXY))
        assertEquals(GatewayErrorCodes.UPSTREAM_PROXY_FORBIDDEN, code("http://127.0.0.1:7890", role = NetGuard.UrlRole.PROXY))
        assertEquals(GatewayErrorCodes.UPSTREAM_PROXY_FORBIDDEN, code("http://nx.example:3128", resolver = FakeResolver(), role = NetGuard.UrlRole.PROXY))
        val corp = OutboundAddressPolicy(allowedNetworks = listOf(IpNetwork.parse("10.0.0.0/8")))
        assertNull(code("http://10.1.1.1:3128", corp, role = NetGuard.UrlRole.PROXY))
    }

    @Test fun token_endpoints_must_be_https_and_public() {
        assertNull(code("https://oauth2.googleapis.com/token", role = NetGuard.UrlRole.TOKEN_ENDPOINT))
        assertEquals(GatewayErrorCodes.UPSTREAM_URL_SCHEME_INVALID, code("http://oauth2.googleapis.com/token", role = NetGuard.UrlRole.TOKEN_ENDPOINT))
        assertEquals(GatewayErrorCodes.UPSTREAM_URL_PRIVATE_ADDRESS, code("https://169.254.169.254/token", role = NetGuard.UrlRole.TOKEN_ENDPOINT))
        assertEquals(
            GatewayErrorCodes.UPSTREAM_URL_PRIVATE_ADDRESS,
            code("https://metadata.alias/token", resolver = FakeResolver(mapOf("metadata.alias" to listOf("169.254.169.254"))), role = NetGuard.UrlRole.TOKEN_ENDPOINT),
        )
    }

    @Test fun request_time_rejections_map_to_the_same_codes() {
        assertEquals(GatewayErrorCodes.UPSTREAM_URL_PRIVATE_ADDRESS, NetGuard.rejectionCode(TargetRejection.ADDRESS))
        assertEquals(GatewayErrorCodes.UPSTREAM_URL_UNRESOLVABLE, NetGuard.rejectionCode(TargetRejection.RESOLVE))
        assertEquals(GatewayErrorCodes.UPSTREAM_URL_PORT_FORBIDDEN, NetGuard.rejectionCode(TargetRejection.PORT))
        assertEquals(GatewayErrorCodes.UPSTREAM_URL_SCHEME_INVALID, NetGuard.rejectionCode(TargetRejection.SCHEME))
        assertEquals(GatewayErrorCodes.UPSTREAM_PROXY_FORBIDDEN, NetGuard.rejectionCode(TargetRejection.PROXY))
        assertNull(NetGuard.rejectionCode(neton.http.client.HttpClientError.Network("reset", null)))
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


}
