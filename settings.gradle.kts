pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

rootProject.name = "netonapi-backend"

// 框架（com.netonstream:neton-*）、neton-ai 与 geolite4k 默认用 Maven Central 上的发布版，版本见 gradle.properties。
// 联调尚未发布的框架改动时加 -Pnetonapi.frameworkSource=true，改用 ../../Neton/ 下的源码（composite build）。
if (providers.gradleProperty("netonapi.frameworkSource").orNull == "true") {
    includeBuild("../../Neton/neton")
    includeBuild("../../Neton/neton-ai")
    includeBuild("../../Neton/geolite4k")
}

// 套件基础契约（infra / account 等模块依赖 com.netonstream.app:application-contract），与模块一样按源码子项目装配
include(":application-contract")
project(":application-contract").projectDir = file("../../Neton/neton-application-contract")

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
