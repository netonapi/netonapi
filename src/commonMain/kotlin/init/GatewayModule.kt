package init

import neton.core.annotations.Module

/**
 * gateway 模块声明锚点：LLM 中转站领域（渠道/令牌/定价/额度/用量）。
 * KSP 生成 GatewayModuleManifest（@Logic 装配 + 路由 + migrations）。
 */
@Module(dependsOn = ["system"], migrations = true)
object GatewayModule {
    const val MODULE_ID = "gateway"
}
