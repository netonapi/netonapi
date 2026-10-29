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
        // 在 macOS 上交叉编译 Linux 时，链接器找不到目标机的 libcurl.so：把它从目标机拷到本地某目录，
        // 用 -Pnetonapi.linuxX64.libDir=<dir>（或 NETONAPI_LINUX_X64_LIBDIR）指过来。Linux 本机构建不需要。
        val envName = target.name.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
        val extraLibDir = providers.gradleProperty("netonapi.${target.name}.libDir")
            .orElse(providers.environmentVariable("NETONAPI_${envName}_LIBDIR"))
            .orNull
        target.binaries.forEach { binary ->
            binary.linkerOpts.add("-L$coreInterop")
            binary.linkerOpts.add("-lenv")
            if (extraLibDir != null) binary.linkerOpts.add("-L${file(extraLibDir).absolutePath}")
        }
    }

    sourceSets {
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        commonMain {
            dependencies {
                // 核心模块
                implementation(project(":module-system"))
                implementation(project(":module-infra"))

                // 通用 base distribution 默认模块。产品专属模块（IM / game / assistant 等）不在
                // base 里，由具体产品发行版 fork 自行加回。
                implementation(project(":module-account"))
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
    arg("neton.modules", "system,infra,account,member,payment,platform,gateway")
}

// linuxX64 不用 Kotlin/Native 编译器缓存：debug 链接（单测 / harness）时，缓存里 cryptography-kotlin 预编译的
// OpenSSL 会被整体链入，与 openssl-kotlin 源码编译的那份符号重复（ld.lld: duplicate symbol OPENSSL_*）。
// Kotlin 2.4 起旧的 kotlin.native.cacheKind 属性已不再生效，只能用这个 DSL；release 链接本就不走缓存。
kotlin.linuxX64().binaries.all {
    @OptIn(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi::class)
    disableNativeCache(
        org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion.`2_4_20`,
        "cryptography-kotlin's prebuilt OpenSSL and openssl-kotlin's OpenSSL collide when linked from caches",
    )
}

// KSP 只在一个「生成主机」目标上跑，产物给所有目标共用（生成的是平台无关代码）：
// Mac 上用 macosArm64，其他主机用 linuxX64 —— Linux 上编不了 Apple 目标，CI / Docker 的 Linux 构建因此不能依赖它。
val kspHost = if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) "macosArm64" else "linuxX64"
val kspHostTask = "kspKotlin" + kspHost.replaceFirstChar { it.uppercase() }

// Ensure Kotlin compilation sees KSP-generated commonMain sources.
// Required because application Main references generated GeneratedNetonConfigRegistry —
// K2 metadata compilation needs it at the commonMain level.
afterEvaluate {
    val kspOut = file("build/generated/ksp/$kspHost/${kspHost}Main/kotlin")
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
    dependsOn(kspHostTask)
}
tasks.matching { it.name.matches(Regex("compileKotlin(MacosArm64|LinuxX64|LinuxArm64|MingwX64)")) }.configureEach {
    dependsOn(kspHostTask)
}
tasks.matching { it.name.matches(Regex("kspKotlin(MacosArm64|LinuxX64|LinuxArm64|MingwX64)")) && it.name != kspHostTask }.configureEach {
    dependsOn(kspHostTask)
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
