pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

rootProject.name = "newgate"

// 框架依赖（composite build）—— canonical 仓位于 ../../Neton/，所有下游 fork 共用这个布局约定
includeBuild("../../Neton/neton")
includeBuild("../../Neton/geolite4k")

// Canonical sibling repositories are assembled as source subprojects here.
// Each repository keeps its own settings.gradle.kts and remains independently buildable.
// application 仅做装配 —— 所有模块（含 system/infra 核心）都是独立 canonical 仓。
include(":module-system")
project(":module-system").projectDir = file("../../Neton/neton-application-module-system")
include(":module-infra")
project(":module-infra").projectDir = file("../../Neton/neton-application-module-infra")
include(":module-member")
project(":module-member").projectDir = file("../../Neton/neton-application-module-member")
include(":module-payment")
project(":module-payment").projectDir = file("../../Neton/neton-application-module-payment")
include(":module-platform")
project(":module-platform").projectDir = file("../../Neton/neton-application-module-platform")
include(":module-gateway")
project(":module-gateway").projectDir = file("../../Neton/neton-application-module-gateway")

// 应用入口
include(":application")
