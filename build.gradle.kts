plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

group = "com.netonstream.app"
version = "1.0.0"

repositories {
    mavenCentral()
}

// AES-GCM（渠道 Key 加密）在 Apple 上由 CryptoKit 提供，其实现是 Swift，链接需要
// macOS SDK 里的 Swift 运行时存根。Kotlin/Native 不会传这个目录给 ld，Xcode 15 上会
// 报 "Could not find or use auto-linked library 'swift_*'"。非 macOS 主机为 null，跳过。
val appleSwiftLibDir: String? by lazy {
    if (!org.gradle.internal.os.OperatingSystem.current().isMacOsX) return@lazy null
    runCatching {
        providers.exec {
            commandLine("xcrun", "--sdk", "macosx", "--show-sdk-path")
        }.standardOutput.asText.get().trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { "$it/usr/lib/swift" }
}

kotlin {
    listOf(macosArm64(), linuxX64(), linuxArm64(), mingwX64()).forEach { target ->
        val coreInterop = file("$projectDir/../neton/neton-core/build/nativeInterop/${target.name}").absolutePath
        target.binaries.forEach { binary ->
            binary.linkerOpts.add("-L$coreInterop")
            binary.linkerOpts.add("-lenv")
            if (target.konanTarget.family.isAppleFamily) {
                appleSwiftLibDir?.let { binary.linkerOpts.add("-L$it") }
            }
        }
    }

    sourceSets {
        // 平台 DNS 解析（SSRF 要看得懂域名指向哪里）：posix 一份 actual（getaddrinfo），
        // mingw 一份 actual（显式降级为 null）。本模块没有套用 Kotlin 默认层级模板
        // （nativeMain/appleMain 都不存在），所以只新建一层 posixMain 并挂到三个 posix 目标；
        // mingwX64Main 由 target 声明自带，直接放 src/mingwX64Main/kotlin 即可。
        val posixMain by creating { dependsOn(commonMain.get()) }
        val macosArm64Main by getting { dependsOn(posixMain) }
        val linuxX64Main by getting { dependsOn(posixMain) }
        val linuxArm64Main by getting { dependsOn(posixMain) }

        commonMain {
            dependencies {
                implementation("com.netonstream.app:module-system")
                implementation("com.netonstream:neton-core")
                implementation("com.netonstream:neton-routing")
                implementation("com.netonstream:neton-security")
                implementation("com.netonstream:neton-http")
                implementation("com.netonstream:neton-database")
                implementation("com.netonstream:neton-logging")
                implementation("com.netonstream:neton-validation")
                implementation("com.netonstream:neton-redis")
                implementation("com.netonstream:neton-cache")
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

ksp {
    arg("neton.moduleId", "gateway")
}

// Ensure Kotlin compilation sees KSP-generated commonMain sources.
// Required because facade tables delegate to generated XxxTableImpl —
// K2 metadata compilation needs them at the commonMain level.
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

// Embed SQL migration resources into binary (no runtime file IO).
// 迁移方言跟随编译期 sqlx4k 驱动(neton.database.driver，与框架同名)：sqlx4k Native 驱动编译期单选、
// 不能运行时切换，故 PostgreSQL / MySQL 各产出独立二进制/镜像。NewGate 仅支持 postgres|mysql(不含 sqlite)。默认 postgres。
// 与框架一致：neton.database.driver 大小写不敏感（框架侧同样 lowercase() 归一），避免 =MYSQL 时框架接受、gateway 拒绝。
val ngMigrationDialect = when (val d = ((project.findProperty("neton.database.driver") as? String) ?: "postgres").lowercase()) {
    "postgres" -> "postgresql"
    "mysql" -> "mysql"
    else -> error("NewGate 仅支持 -Pneton.database.driver=postgres|mysql；收到 '$d'")
}
extra["neton.migration.moduleId"] = "gateway"
extra["neton.migration.dialects"] = listOf(ngMigrationDialect)
extra["neton.migration.sqlSourceDir"] = file("sql")
apply(from = "../neton/scripts/embed-migration-resources.gradle.kts")
