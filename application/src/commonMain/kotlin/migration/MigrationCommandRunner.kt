// 迁移命令在不启动整个应用的情况下直接初始化数据库连接（SqlxDatabase），属于贴着框架的集成代码：
// 框架把这些入口标为内部 API（InternalNetonApi），这里显式声明使用。
@file:OptIn(neton.core.annotations.InternalNetonApi::class)

package migration

import kotlinx.coroutines.runBlocking
import neton.core.module.ModuleInitializer
import neton.database.adapter.sqlx.SqlxDatabase
import neton.database.migration.MigrationCommand
import neton.database.migration.MigrationEngine
import neton.database.migration.MigrationResult

/**
 * `./application.kexe migrate <status|up|verify>` 子命令编排。
 *
 * 边界:
 *   - 由 `Main.kt` 在 `args.firstOrNull() == "migrate"` 时调用,执行完 `exitProcess(code)`
 *   - **不**走 `Neton.run`,**不**启动 HTTP / scheduler / WebSocket / controllers / seedData
 *   - 配置解析: [MigrationConfigLoader]
 *   - 模块 sources 聚合: [ApplicationMigrationSources](与 startup precheck 复用同一份逻辑)
 *   - 引擎: [neton.database.migration.MigrationEngine](DB-MIG-1/2 frozen contract)
 *
 * 退出码契约(SPEC §5.4):
 *   - 0  全成功 / 无事可做 / 全 ok
 *   - 1  status: 检测到 pending(CI dry-run 提示)
 *   - 2  up 执行失败
 *   - 3  checksum 不一致 / verify 无 history 表
 *   - 4  数据库连接失败
 *   - 64 命令行参数错误
 */
internal object MigrationCommandRunner {

    private const val EXIT_OK = 0
    private const val EXIT_PENDING = 1
    private const val EXIT_FAILED = 2
    private const val EXIT_CHECKSUM_MISMATCH = 3
    private const val EXIT_DB_CONNECT_FAILED = 4
    private const val EXIT_USAGE_ERROR = 64

    fun run(args: Array<String>, modules: List<ModuleInitializer>): Int {
        val command = when (val sub = args.getOrNull(1)) {
            "status" -> MigrationCommand.STATUS
            "up" -> MigrationCommand.UP
            "verify" -> MigrationCommand.VERIFY
            null -> {
                println("usage: application.kexe migrate <status|up|verify>")
                return EXIT_USAGE_ERROR
            }
            else -> {
                println("unknown migrate subcommand: '$sub' (expected: status | up | verify)")
                return EXIT_USAGE_ERROR
            }
        }
        return try {
            runBlocking { runEngine(args, modules, command) }
        } catch (e: Throwable) {
            println("migrate failed: ${e.message}")
            e.printStackTrace()
            EXIT_FAILED
        }
    }

    private suspend fun runEngine(
        args: Array<String>,
        modules: List<ModuleInitializer>,
        command: MigrationCommand,
    ): Int {
        // ----- 1. 配置 -----
        val cfg = when (val r = MigrationConfigLoader.load(args)) {
            is MigrationConfigLoader.Result.Ok -> r
            is MigrationConfigLoader.Result.Failure -> {
                println(r.message)
                return EXIT_USAGE_ERROR
            }
        }

        // ----- 2. 初始化 SqlxDatabase (复用 application 链接的 driver,不启动其他组件) -----
        val dbContext = try {
            SqlxDatabase.initialize(cfg.database)
        } catch (e: Throwable) {
            println("database connect failed: ${e.message}")
            return EXIT_DB_CONNECT_FAILED
        }

        return try {
            // ----- 3. 聚合 sources(模块自管,application 不改写) -----
            val sources = ApplicationMigrationSources.collect(modules, cfg.migration.dialect)

            // ----- 4. 跑 engine -----
            val engine = MigrationEngine(dbContext, cfg.migration)
            val result = engine.run(command, sources)

            // ----- 5. 格式化 + exit code -----
            formatAndExitCode(result)
        } finally {
            SqlxDatabase.close()
        }
    }

    // ============================================================
    // 输出格式 + exit code
    // ============================================================

    private fun formatAndExitCode(result: MigrationResult): Int {
        for (w in result.warnings) println("WARN: $w")
        return when (result) {
            is MigrationResult.Aborted -> {
                println("ABORTED: ${result.reason}")
                when {
                    result.reason.contains("checksum", ignoreCase = true) -> EXIT_CHECKSUM_MISMATCH
                    result.reason.contains("does not exist") -> EXIT_CHECKSUM_MISMATCH
                    else -> EXIT_FAILED
                }
            }
            is MigrationResult.Status -> formatStatus(result)
            is MigrationResult.Up -> formatUp(result)
            is MigrationResult.Verify -> formatVerify(result)
        }
    }

    private fun formatStatus(r: MigrationResult.Status): Int {
        println("History table: ${r.historyTable}")
        if (!r.historyExists) println("(history table does not exist yet)")
        for (s in r.scripts) {
            val tag = s.state.name.lowercase().padEnd(18)
            val err = s.errorMessage?.let { "  err=${it.take(80)}" } ?: ""
            println("  [$tag] [${s.moduleId}] V${s.version}  ${s.description}$err")
        }
        println(
            "Summary: ${r.executedCount} executed, ${r.pendingCount} pending, " +
                "${r.mismatchCount} changed, ${r.failedCount} failed"
        )
        return when {
            r.failedCount > 0 -> EXIT_FAILED
            r.mismatchCount > 0 -> EXIT_CHECKSUM_MISMATCH
            r.pendingCount > 0 -> EXIT_PENDING
            else -> EXIT_OK
        }
    }

    private fun formatUp(r: MigrationResult.Up): Int {
        for (s in r.applied) {
            println("APPLIED [${s.moduleId}] V${s.version}  ${s.description}  (${s.executionMs}ms)")
        }
        r.failedAt?.let { f ->
            println("FAILED  [${f.moduleId}] V${f.version}  ${f.description}  err=${f.errorMessage}")
            println("Aborted. ${r.applied.size} migration(s) applied successfully before failure.")
            return EXIT_FAILED
        }
        if (r.applied.isEmpty()) {
            println("Nothing to migrate. ${r.skipped} script(s) already executed.")
        } else {
            println("Done. ${r.applied.size} migration(s) applied; ${r.skipped} previously executed.")
        }
        return EXIT_OK
    }

    private fun formatVerify(r: MigrationResult.Verify): Int {
        for (m in r.mismatches) {
            println(
                "MISMATCH [${m.moduleId}] V${m.version}  ${m.description}\n" +
                    "  disk:    ${m.diskChecksum}\n" +
                    "  history: ${m.historyChecksum}"
            )
        }
        for (m in r.missing) {
            println("MISSING  [${m.moduleId}] V${m.version}  (in history but no file on disk)")
        }
        println(
            "Verified ${r.verifiedCount} executed script(s): " +
                "${r.mismatches.size} mismatched, ${r.missing.size} missing"
        )
        return if (r.ok) {
            println("All checksums match.")
            EXIT_OK
        } else {
            EXIT_CHECKSUM_MISMATCH
        }
    }
}
