pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

rootProject.name = "module-gateway"

// 框架
includeBuild("../neton")

// canonicalize：统一用 ../../Neton/ 前缀指向 Neton canonical 工作区（跨工作区一致）
