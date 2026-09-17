package init

import neton.core.annotations.Module

/**
 * gateway 模块声明锚点：LLM 中转站领域（渠道/令牌/定价/额度/用量）。
 * KSP 生成 GatewayModuleManifest（@Logic 装配 + 路由 + migrations）。
 */
// dependsOn 列全了五个模块，不是为了拿它们的 Logic —— 网关运行时只用 system。
// 列在这里是因为**迁移顺序**：V012 要调整这些模块种下的菜单行（停用与本产品无关的页面、
// 重排、补角色），而模块顺序是按 dependsOn 拓扑排的。只写 "system" 时排序结果是
// system → infra → payment → platform → gateway → member，member 的菜单在 V012 跑完之后才插进去，
// 于是那批 UPDATE 一行都匹配不到，还不报错 —— 安静地少停用几页。声明真实依赖让 gateway 排在最后。
@Module(dependsOn = ["system", "infra", "member", "payment", "platform"], migrations = true)
object GatewayModule {
    const val MODULE_ID = "gateway"
}
