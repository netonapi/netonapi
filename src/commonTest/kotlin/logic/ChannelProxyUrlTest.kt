package logic

import model.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 管理台表单里代理留空提交的是 ""：曾原样落库，建 HTTP 客户端时被当成非法代理地址拒绝，
 * 这样的渠道拉模型、转发全部失败（2026-10-27 线上 4 条真实渠道）。空白一律等于「不走代理」。
 */
class ChannelProxyUrlTest {

    private fun ch(proxy: String?) = Channel(name = "c", type = "openai_compatible", baseUrl = "https://api.example.com/v1", proxyUrl = proxy)

    @Test fun blankProxyMeansNoProxy() {
        assertNull(effectiveProxyUrl(""))
        assertNull(effectiveProxyUrl("   "))
        assertNull(effectiveProxyUrl(null))
        assertNull(ch("").effectiveProxyUrl())
    }

    @Test fun realProxyIsTrimmed() {
        assertEquals("http://10.0.0.1:3128", effectiveProxyUrl("  http://10.0.0.1:3128 "))
    }

    @Test fun blankAndNullProxyShareOneClient() {
        assertEquals(ChannelClientCache.fingerprintOf(ch(null)), ChannelClientCache.fingerprintOf(ch("")))
    }
}
