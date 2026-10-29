pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

rootProject.name = "netonapi-backend"

// 框架依赖（composite build）—— canonical 仓位于 ../../Neton/，所有下游 fork 共用这个布局约定
includeBuild("../../Neton/neton")
// neton-ai 已从框架拆成独立仓库（netonframework/neton-ai）：网关的协议互转依赖它
includeBuild("../../Neton/neton-ai")
includeBuild("../../Neton/geolite4k")
// 套件基础契约（infra / account 等模块依赖 com.netonstream.app:application-contract）
includeBuild("../../Neton/neton-application-contract")

// Canonical sibling repositories are assembled as source subprojects here.
// Each repository keeps its own settings.gradle.kts and remains independently buildable.
// application 仅做装配 —— 所有模块（含 system/infra 核心）都是独立 canonical 仓。
include(":module-system")
project(":module-system").projectDir = file("../../Neton/neton-application-module-system")
include(":module-infra")
project(":module-infra").projectDir = file("../../Neton/neton-application-module-infra")
include(":module-account")
project(":module-account").projectDir = file("../../Neton/neton-application-module-account")
include(":module-member")
project(":module-member").projectDir = file("../../Neton/neton-application-module-member")
include(":module-payment")
project(":module-payment").projectDir = file("../../Neton/neton-application-module-payment")
include(":module-platform")
project(":module-platform").projectDir = file("../../Neton/neton-application-module-platform")
include(":module-gateway")
// NetonAPI 独有的网关模块就在本仓里（其余模块是 Neton 套件的通用模块）
project(":module-gateway").projectDir = file("module-gateway")

// 应用入口
include(":application")
