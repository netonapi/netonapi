package config

import neton.core.component.NetonContext
import neton.core.interfaces.Identity
import neton.core.interfaces.RequestContext
import neton.security.SecurityBuilderImpl
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SecurityConfigTest {
    private val request = object : RequestContext {
        override val path = "/admin/system/user/query"
        override val method = "GET"
        override val headers = emptyMap<String, String>()
        override val routeGroup = "admin"
    }

    private fun identity(roles: Set<String>, permissions: Set<String>) = object : Identity {
        override val id = "1"
        override val roles = roles
        override val permissions = permissions
    }

    @Test
    fun configuredEvaluatorPreservesWildcardPolicyWithoutRoleBypass() {
        val ctx = NetonContext(arrayOf(
            "--security.jwt.secretKey=test-only-secret-not-for-production",
            "--security.unified_token.enabled=false",
            "--security.unified_token.verify_legacy_jwt=true",
        ))
        val builder = SecurityBuilderImpl()
        SecurityConfig().configure(ctx, builder)
        val evaluator = assertNotNull(builder.build().permissionEvaluator)
        assertTrue(evaluator.allowed(identity(setOf("root"), setOf("*:*:*")), "system:user:query", request))
        assertTrue(evaluator.allowed(identity(emptySet(), setOf("system:user:*")), "system:user:query", request))
        assertFalse(evaluator.allowed(identity(setOf("super_admin"), emptySet()), "system:user:query", request))
        assertFalse(evaluator.allowed(identity(emptySet(), setOf("member:user:query")), "system:user:query", request))
    }
}
