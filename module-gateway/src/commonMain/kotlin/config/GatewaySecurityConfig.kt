package config

import neton.core.component.NetonContext
import neton.core.config.NetonConfig
import neton.core.config.NetonConfigurer
import neton.core.interfaces.SecurityBuilder
import security.GatewayTokenAuthenticator

/** gateway 组安全接线：sk- 令牌认证器，不影响 admin/app 组的 JWT 体系。 */
@NetonConfig("security", order = 10)
class GatewaySecurityConfig : NetonConfigurer<SecurityBuilder> {
    override fun configure(ctx: NetonContext, target: SecurityBuilder) {
        target.setGroupAuthenticator("gateway", GatewayTokenAuthenticator(ctx))
    }
}
