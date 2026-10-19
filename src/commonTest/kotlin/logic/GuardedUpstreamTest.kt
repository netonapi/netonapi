package logic

import kotlinx.coroutines.runBlocking
import model.Channel
import neton.http.client.EgressSettings
import neton.http.client.GuardedHttpClientProvider
import neton.http.client.HttpClient
import neton.http.client.HttpClientError
import neton.http.client.HttpClientException
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientProvider
import neton.http.client.HttpClientRequest
import neton.http.client.IpNetwork
import neton.http.client.create
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 请求时强制：渠道流量（转发 / 探测 / 拉模型）与 Vertex 换 token 都走 [RelayEngine.guardedClients] 的受控客户端，
 * 写库绕过写入时校验的渠道照样在连接前被拒；运维 `[egress]` 放行的内网上游能连。
 */
class GuardedUpstreamTest {

    private fun install(settings: EgressSettings) {
        val guarded = GuardedHttpClientProvider(settings, HttpClientProvider { HttpClient.create(it) })
        RelayEngine.guardedClients = guarded
        RelayEngine.channelClients = RelayEngine.channelClientCache(guarded)
    }

    @AfterTest fun reset() = runBlocking {
        RelayEngine.channelClients?.close()
        RelayEngine.channelClients = null
        RelayEngine.guardedClients = null
    }

    private suspend fun send(channel: Channel): HttpClientError {
        try {
            RelayEngine.withChannelClient(channel) {
                it.request(HttpClientRequest(method = HttpClientMethod.Get, url = channel.baseUrl.trimEnd('/') + "/v1/models"))
            }
        } catch (e: HttpClientException) {
            return e.error
        }
        fail("expected the request to fail")
    }

    @Test fun channels_pointing_inside_are_refused_at_request_time() = runBlocking {
        install(EgressSettings())
        // 直接写进库的渠道（绕过写入时校验）：回环、云元数据、写成十进制的回环
        for ((id, base) in listOf(9101L to "http://127.0.0.1", 9102L to "http://169.254.169.254", 9103L to "http://2130706433")) {
            val err = send(Channel(id = id, name = "c$id", type = "openai", baseUrl = base))
            assertEquals(GatewayErrorCodes.UPSTREAM_URL_PRIVATE_ADDRESS, NetGuard.rejectionCode(err), "$base: $err")
        }
        val port = send(Channel(id = 9104L, name = "p", type = "openai", baseUrl = "http://8.8.8.8:6379"))
        assertEquals(GatewayErrorCodes.UPSTREAM_URL_PORT_FORBIDDEN, NetGuard.rejectionCode(port), "$port")
    }

    @Test fun a_private_proxy_is_refused_at_request_time() = runBlocking {
        install(EgressSettings())
        val err = send(Channel(id = 9111L, name = "px", type = "openai", baseUrl = "https://8.8.8.8", proxyUrl = "http://127.0.0.1:7890"))
        assertEquals(GatewayErrorCodes.UPSTREAM_PROXY_FORBIDDEN, NetGuard.rejectionCode(err), "$err")
    }

    @Test fun operator_allowlisted_private_upstream_is_dialled() = runBlocking {
        // 放行 127.0.0.0/8 与端口 9：地址通过准入，于是真的去连（本机 9 端口没人听 → 网络错误，而不是被拒）
        install(EgressSettings(allowedPorts = setOf(9), allowedNetworks = listOf(IpNetwork.parse("127.0.0.0/8"))))
        val err = send(Channel(id = 9121L, name = "local", type = "openai", baseUrl = "http://127.0.0.1:9"))
        assertEquals(null, NetGuard.rejectionCode(err), "admitted target must not be refused: $err")
        assertTrue(err is HttpClientError.Network || err is HttpClientError.Timeout, "$err")
        // 放行的是 127.0.0.0/8，不是整个内网
        val other = send(Channel(id = 9122L, name = "meta", type = "openai", baseUrl = "http://169.254.169.254:9"))
        assertEquals(GatewayErrorCodes.UPSTREAM_URL_PRIVATE_ADDRESS, NetGuard.rejectionCode(other), "$other")
    }

    private fun serviceAccount(tokenUri: String) =
        """{"type":"service_account","client_email":"probe-${tokenUri.hashCode()}@example.iam.gserviceaccount.com","private_key":"$KEY","token_uri":"$tokenUri"}"""

    @Test fun vertex_token_uri_goes_through_the_guarded_client() = runBlocking {
        install(EgressSettings())
        for (uri in listOf("https://169.254.169.254/token", "https://127.0.0.1/token")) {
            val e = assertFailsWith<HttpClientException> { logic.protocol.vertex.VertexAuth.bearer(serviceAccount(uri)) }
            assertEquals(GatewayErrorCodes.UPSTREAM_URL_PRIVATE_ADDRESS, NetGuard.rejectionCode(e.error), "$uri: ${e.error}")
        }
        // 断言绝不走明文
        assertFailsWith<IllegalArgumentException> { logic.protocol.vertex.VertexAuth.bearer(serviceAccount("http://oauth2.googleapis.com/token")) }
        Unit
    }

    private companion object {
        /** 测试专用的一次性 RSA 私钥（只用来让断言签得出来，请求在连接前就被拒）。 */
        const val KEY = "-----BEGIN PRIVATE KEY-----\\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC+FSZsUaydHwOV\\neG69kmwxIXKUL2efmXGWvg9tvWrvpBQqHDWQGCLALI8XyP308qv37EGP/wDBbvR1\\n9wQoKeiGoe/T1ozC9hNea83W9JkIvNoAVd+aSNtpp76CSnQQRDn8FAjyULJx5TOt\\nZ4ROM8hJcYXUr7as2bQkHX+2e3zgWBoDvlBTfrRk27CwVj9NH2mmGJ0qNh+EG7Nd\\nTFHJH9mr5+SdLxVFvw313EJ/S742b147VrBQ/ITMB6LTkndMRi65tRfc6E962erC\\ndOEKACzqNo1r3JSGDZRgf3Qpva7XMqKMh0ZrVL3yV8A4+c7vEJf7i76lsCtF3MIW\\nW0zF+Hz5AgMBAAECggEABEZB+7AXf14F7NvNM7ah97Xel338aM6gywjBnJBkofhw\\n2qSRQMkGKQenfRuwOEynuNbHf6c2m2L1HNz96zBMEhD8rWkW98BkXZxzf8i200c5\\n6MWFlnjadEUJJ/FoLEB4hFZoABj39k826wiXpVGNSLeEkKyV4Uuw/RnruA0NWRcg\\nJ0gmjPqVkSHLVl7SzxvKjh7zQUhd93V4nBVJMlnZjfIK9hIkW5uBMCUNdsWux+LS\\n6vpG7fnwltJhkVQNqLfdXh3HrxwzdiNxlL9cvySbrX9lGJHfcrK7hyPS1eDswrqa\\nfqsDMTHo/JGdXDhO23HN/P48ogMspuU1OdzUSdmBvQKBgQDroHgf08+2HXgEIeUC\\nuiPlIuare7abdN7JuH6mUKSL5NPOuc2cH+omiIFvJek7xmIOpHbEV1ivSrmPR90L\\n0bGGRIqiR3iOhJXYJMX018xenlalcXkB+7yBkMAWJ1jBvf1MP2ZjyB8hDhq58/AD\\nnrczKXvUqv/6iYfCiEcAmb0WpQKBgQDOhJKxXdSt/SdnOAnv2eF6BLxdxgOeqrdS\\nCC6BikdLjhX4E+nune4ggdqRXwE5gy9ZF0DduwqdDGfY+GJvCTupdbRqvwQLE2DT\\nkhklK8HMdD+wZesw0WsL+OX6T2NLkW/WXNj+vfFFbxliMGzABcHu888ZfPVMCfWy\\n+0mkg9DQxQKBgCZLNxqdFuXXFjk9tPVjgQ/tQyXdfkj3jnmkNVkcRBHER7QSOFtG\\nSIyopLiGpcgkLq2IhxDTzUL31v51RHR2SO60Vv+Ws/4diCjPmSh4pes+uoA619q9\\nqd44wy8Wh/M8yFzK9P4EeSKrIybTgU9lTQrQlCJ1BBMYpOq1sSxkz/lNAoGADwWH\\nog0gLjOuzQw/dMnVY8m6ZdvEYaMrSHU95MBJJU7FfFD+gBPOwWCQpGhTr3pCVGmj\\nlz1GrVu8pgSTLF5wj4f3Qph4U9HqBhmnaJPBOfzusQcIz3k8HxfQqp9hqpp+lwoT\\n4puwVAubvg+lqWYB0D69vq3fLdO/onAy0Buicb0CgYEAycEG2j+pwKitoBR8yKMX\\nvMQDah66qOk0KifV/lt0tKTaO1rgWGJCCSfr/xLgISBGq33tHQ/oXd0v9HnLEyib\\nNX8Vk8zv7BZ2y/h3+oqhPRkA9qJacu8NKxZ5NFyXXbwr9Yt7ltXPV87GQ30TaSqx\\nMt9p6pHDsVNKqQNtmcMZdr4=\\n-----END PRIVATE KEY-----"
    }
}
