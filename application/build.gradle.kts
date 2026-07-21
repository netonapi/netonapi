plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

repositories {
    mavenCentral()
}

kotlin {
    listOf(macosArm64(), linuxX64(), linuxArm64(), mingwX64()).forEach { target ->
        target.binaries {
            executable {
                entryPoint = "main"
            }
        }
        val coreInterop = rootProject.file("../../Neton/neton/neton-core/build/nativeInterop/${target.name}").absolutePath
        target.binaries.forEach { binary ->
            binary.linkerOpts.add("-L$coreInterop")
            binary.linkerOpts.add("-lenv")
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                // 核心模块
                implementation(project(":module-system"))
                implementation(project(":module-infra"))

                // 通用 base distribution 默认模块。R5-B: game/assistant(产品模块)
                // 与 com.netonstream.privchat:main(可选 IM 模块)移出 base,由
                // privchat-application 产品发行版 fork 加回。
                implementation(project(":module-member"))
                implementation(project(":module-gateway"))
                implementation(project(":module-payment"))
                implementation(project(":module-platform"))

                // 框架依赖
                implementation("com.netonstream:neton-core")
                implementation("com.netonstream:neton-routing")
                implementation("com.netonstream:neton-security")
                implementation("com.netonstream:neton-http")
                implementation("com.netonstream:neton-database")
                implementation("com.netonstream:neton-logging")
                implementation("com.netonstream:neton-validation")
                implementation("com.netonstream:neton-redis")
                implementation("com.netonstream:neton-cache")
                implementation("com.netonstream:neton-storage")
                implementation("com.netonstream:neton-jobs")
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

dependencies {
    add("kspMacosArm64", "com.netonstream:neton-ksp")
    add("kspLinuxX64", "com.netonstream:neton-ksp")
    add("kspLinuxArm64", "com.netonstream:neton-ksp")
    add("kspMingwX64", "com.netonstream:neton-ksp")
}

// MANIFEST-P2: application 模块 registry 唯一来源。引入/移除模块 = settings.gradle
// dependency + 这里加/删一个 id。KSP ApplicationRegistryProcessor 生成
// neton.application.generated.GeneratedApplicationModules —— Main.kt 的 migrate
// 子命令 / startup precheck / serve modules(...) 三处共用，不再手写模块列表。
// 顺序 = 声明顺序（Neton.run 内部仍按 dependsOn 拓扑排序兜底）。
ksp {
    arg("neton.modules", "system,infra,member,payment,platform,gateway")
}

// Ensure Kotlin compilation sees KSP-generated commonMain sources.
// Required because application Main references generated GeneratedNetonConfigRegistry —
// K2 metadata compilation needs it at the commonMain level.
afterEvaluate {
    val kspOut = file("build/generated/ksp/macosArm64/macosArm64Main/kotlin")
    kotlin.sourceSets.named("commonMain") {
        kotlin.srcDir(kspOut)
    }
    listOf("macosArm64Main", "linuxX64Main", "linuxArm64Main", "mingwX64Main").forEach { name ->
        kotlin.sourceSets.findByName(name)?.let { ss ->
            val filtered = ss.kotlin.srcDirs.filter { !it.path.contains("generated/ksp") }
            if (filtered.size < ss.kotlin.srcDirs.size) ss.kotlin.setSrcDirs(filtered)
        }
    }
}

tasks.matching { it.name == "compileCommonMainKotlinMetadata" }.configureEach {
    dependsOn("kspKotlinMacosArm64")
}
tasks.matching { it.name.matches(Regex("compileKotlin(MacosArm64|LinuxX64|LinuxArm64|MingwX64)")) }.configureEach {
    dependsOn("kspKotlinMacosArm64")
}
tasks.matching { it.name.matches(Regex("kspKotlin(LinuxX64|LinuxArm64|MingwX64)")) }.configureEach {
    dependsOn("kspKotlinMacosArm64")
}

tasks.matching { it.name.startsWith("linkDebugExecutable") }.configureEach {
    val targetName = when {
        name.contains("MacosArm64") -> "MacosArm64"
        name.contains("LinuxX64") -> "LinuxX64"
        name.contains("LinuxArm64") -> "LinuxArm64"
        name.contains("MingwX64") -> "MingwX64"
        else -> return@configureEach
    }
    dependsOn(gradle.includedBuild("neton").task(":neton-core:archivePosixEnv$targetName"))
}

// SQL embed (2026-06-01 DB-MIG embed refactor):
// 不再有 copyModuleMigrations / migrations/ 目录。SQL 由各模块的 build.gradle.kts
// `generateMigrationResources` task 在编译期转成 Kotlin 字符串常量并 embed 进 binary。
// 运行期 application.kexe 自包含,无 sidecar SQL 文件,无 cwd-relative 路径依赖。
