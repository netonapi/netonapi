package logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
