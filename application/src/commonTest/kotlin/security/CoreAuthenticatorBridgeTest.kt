package security

import kotlinx.coroutines.test.runTest
import neton.core.interfaces.Identity
import neton.core.interfaces.RequestContext as CoreRequestContext
import neton.security.Authenticator as SecurityAuthenticator
import neton.security.RequestContext as SecurityRequestContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CoreAuthenticatorBridgeTest {
    private val identity = object : Identity {
        override val id = "1"
        override val roles = emptySet<String>()
        override val permissions = emptySet<String>()
    }
    private val delegate = object : SecurityAuthenticator {
        override val name = "fake"
        override suspend fun authenticate(context: SecurityRequestContext): Identity? = identity
    }

    private fun request(authorization: String) = object : CoreRequestContext {
        override val path = "/admin/system/auth/get-permission-info"
        override val method = "GET"
        override val headers = mapOf("Authorization" to authorization)
        override val routeGroup = "admin"
    }

    /**
     * 配置里写 "Bearer "，运行时读到的是 "Bearer"（尾空格不保留）。两种前缀下交给管理员 token
     * 二次校验的都必须是裸 token：曾经少一个 trim，token 以空格开头，所有管理员请求都成了 401。
     */
    @Test
    fun adminTokenReachesValidationWithoutPrefixOrSpace() = runTest {
        for (prefix in listOf("Bearer", "Bearer ")) {
            var seen: String? = null
            val bridge = CoreAuthenticatorBridge(delegate, tokenPrefix = prefix, validateAdminToken = { seen = it; true })
            assertNotNull(bridge.authenticate(request("Bearer a.b.c")), "prefix=[$prefix]")
            assertEquals("a.b.c", seen, "prefix=[$prefix]")
        }
    }

    @Test
    fun rejectedAdminTokenYieldsNoIdentity() = runTest {
        val bridge = CoreAuthenticatorBridge(delegate, validateAdminToken = { false })
        assertNull(bridge.authenticate(request("Bearer a.b.c")))
    }
}
