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
// neton-ai 已从框架拆成独立仓库（netonframework/neton-ai）：协议互转依赖它
includeBuild("../neton-ai")

// canonicalize：统一用 ../../Neton/ 前缀指向 Neton canonical 工作区（跨工作区一致）

// 兄弟模块：build.gradle.kts 按 com.netonstream.app:module-* 声明依赖，而这些 artifact
// 从未发布到任何仓库。includeBuild 让 Gradle 用同工作区的源码去替换那些坐标，本仓才能
// 独立构建。主构建（privchat-application）走 include() + projectDir 把各仓当源码子项目，
// 不读本文件，所以这里只影响单仓构建。
includeBuild("../../Neton/neton-application-module-system")
