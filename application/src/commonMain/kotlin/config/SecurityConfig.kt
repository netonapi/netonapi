package config

import neton.core.component.NetonContext
import neton.core.config.NetonConfig
import neton.core.config.NetonConfigurer
import neton.core.interfaces.SecurityBuilder
import neton.security.jwt.DispatchingJwtAuthenticator
import neton.security.jwt.JwtAuthenticator
import neton.security.jwt.UnifiedTokenAuthenticator
import security.CoreAuthenticatorBridge

/**
 * 通用 application HTTP 鉴权骨架（spec TOKEN_UNIFICATION_SPEC v1.3）。
 *
 * # 边界（CONSOL Step 2）
 *
 * 本 configurer 是 **通用 shell**，只认识框架类型：legacy HS256 [JwtAuthenticator] +
 * [DispatchingJwtAuthenticator] + 可选的 [UnifiedTokenAuthenticator]。
 * **不 import 任何产品模块的类型、不 hardcode 具体产品的 token 服务地址**。
 *
 * RS256/JWKS unified verifier 由**产品模块**自带的 configurer（`order = -10`，先于本
 * configurer 执行）构造并 bind 到 ctx；本 configurer 只 `getOrNull` 取出框架类型
 * [UnifiedTokenAuthenticator] 组进 dispatcher。没有这样的模块、或它未 enable 时
 * unified=null，dispatcher 退化为纯 legacy。
 *
 * ⚠️ NetonAPI 当前装配的六个模块**没有任何一个** bind [UnifiedTokenAuthenticator]，
 * 所以 `security.unified_token.enabled` 必须保持 false；要启用得先自己加一个提供它的模块。
 *
 * # 默认行为
 *
 * `security.unified_token.enabled=false`（默认）→ unified=null，纯 legacy HS256，行为不变。
 */
@NetonConfig("security", order = 0)
class SecurityConfig : NetonConfigurer<SecurityBuilder> {
    override fun configure(ctx: NetonContext, target: SecurityBuilder) {
        val jwtConfig = loadJwtRuntimeConfig(ctx)
        val unifiedConfig = loadUnifiedTokenRuntimeConfig(ctx)

        // 1) Legacy HS256 verifier（始终构造）
        val legacy: JwtAuthenticator = JwtAuthenticator(
            secretKey = jwtConfig.secretKey,
            headerName = jwtConfig.headerName,
            tokenPrefix = jwtConfig.tokenPrefix,
        )

        // 2) Unified verifier —— 由产品模块的 configurer (order=-10) 构造并 bind。
        //    shell 只取框架类型，不关心它来自哪个模块。
        val unified: UnifiedTokenAuthenticator? = ctx.getOrNull(UnifiedTokenAuthenticator::class)
        if (unifiedConfig.enabled && unified == null) {
            error(
                "security.unified_token.enabled=true 但 UnifiedTokenAuthenticator 未绑定到 NetonContext。" +
                    "本发行版没有任何模块提供它（NetonAPI 装配的六个模块都不 bind），" +
                    "请把 security.unified_token.enabled 设回 false；若确实要启用，需自行加一个在 " +
                    "SecurityComponent.start (order=-10) 之前构造并 bind 该类型的模块 configurer。",
            )
        }

        // 3) Dispatcher 包住两者；enabled=false 时 unified=null → 行为等价于纯 legacy
        val dispatcher = DispatchingJwtAuthenticator(
            legacy = legacy,
            unified = unified,
            unifiedEnabled = unifiedConfig.enabled,
            verifyLegacyJwt = unifiedConfig.verifyLegacyJwt,
            headerName = jwtConfig.headerName,
            tokenPrefix = jwtConfig.tokenPrefix,
        )
        // bridge 顺带把 HttpOnly 会话 Cookie 补成 Authorization 头（浏览器端后台用），
        // 显式 Authorization 优先，移动端与服务间调用行为不变。
        target.registerAuthenticator(
            CoreAuthenticatorBridge(
                delegate = dispatcher,
                headerName = jwtConfig.headerName,
                tokenPrefix = jwtConfig.tokenPrefix,
                sessionCookieName = config.SessionCookie.name(ctx),
            ),
        )

        target.bindDefaultGuard()
        // super_admin 角色拥有所有权限
        target.setPermissionEvaluator { identity, permission, _ ->
            identity.hasRole("super_admin") || identity.hasPermission(permission)
        }
    }
}
