plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// Kotlin 版本下限 2.4.20：低于它（包括 2.4.20 的 Beta / RC）直接拒绝编译。
// Neton 各仓走同一条 Kotlin 线 —— 混用时 K/N klib 与 KSP 产物会以很难看懂的方式失败。
run {
    val required = KotlinVersion(2, 4, 20)
    val actual = org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion(logger)
    val parts = actual.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    val parsed = KotlinVersion(parts[0], parts[1], parts[2])
    require(parsed > required || (parsed == required && '-' !in actual)) {
        "Kotlin $actual is below the required minimum $required. Upgrade the Kotlin Gradle plugin."
    }
}

subprojects {
    group = "com.netonstream.app"
    version = "1.0.0"

    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("com.netonstream.app:module-system")).using(project(":module-system"))
            substitute(module("com.netonstream.app:module-infra")).using(project(":module-infra"))
            substitute(module("com.netonstream.app:module-account")).using(project(":module-account"))
            substitute(module("com.netonstream.app:module-member")).using(project(":module-member"))
            substitute(module("com.netonstream.app:module-payment")).using(project(":module-payment"))
            substitute(module("com.netonstream.app:module-platform")).using(project(":module-platform"))
        }
    }
}
