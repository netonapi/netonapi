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
 * 正常启动 (`./application.kexe` 无 args) 之前跑一次的 migration 状态检查。
 *
 * SPEC §0.6 红线: serve 模式**绝不**执行 DDL,只检查 pending → fail-fast。
 *
 * 复用同一个 [ApplicationMigrationSources.collect] —— 跟 `migrate` 子命令一模一样的
 * 模块轮询逻辑,只差最后一步:这里跑 [MigrationCommand.STATUS](read-only),migrate 跑
 * UP/STATUS/VERIFY。
 *
 * 红线允许做的事:
 *   - 连数据库
 *   - 读 history 表(如果存在),按 SPEC §6.4 状态模型决定 pending/failed/mismatch
 *   - 打印诊断信息
 *   - exit 非 0
 *
 * 红线禁止做的事:
 *   - 创建 history 表(history 不存在 = schema 没初始化 = fail-fast)
 *   - 执行 V*.sql
 *   - 建任何业务表
 *   - 写 history
 *
 * **MigrationEngine 的 STATUS 路径不写表**(参 [neton.database.migration.MigrationEngine.runStatus]):
 * 只 `history.exists()` 探测,不 `ensureExists()`。如果 history 表不存在,返回
 * `Status(historyExists=false, scripts=[全部 PENDING])`,我们 fail-fast。
 */
internal object MigrationStartupPrecheck {

    /**
     * @return null = OK(可以继续启动);非空 String = 应 fail-fast 的原因
     */
    fun check(args: Array<String>, modules: List<ModuleInitializer>): String? {
        val cfg = when (val r = MigrationConfigLoader.load(args)) {
            is MigrationConfigLoader.Result.Ok -> r
            is MigrationConfigLoader.Result.Failure ->
                return "migration config invalid: ${r.message}"
        }

        val dbContext = try {
            SqlxDatabase.initialize(cfg.database)
        } catch (e: Throwable) {
            return "database connect failed: ${e.message}"
        }

        try {
            val sources = ApplicationMigrationSources.collect(modules, cfg.migration.dialect)
            if (sources.isEmpty()) return null

            val engine = MigrationEngine(dbContext, cfg.migration)
            val result = runBlocking { engine.run(MigrationCommand.STATUS, sources) }

            return when (result) {
            is MigrationResult.Aborted ->
                "migration precheck aborted: ${result.reason}\n" +
                    "please run: ./application.kexe migrate up"

            is MigrationResult.Status -> when {
                // history 表不存在 → schema 未初始化
                !result.historyExists -> buildString {
                    appendLine("schema not initialized: migration history table '${cfg.migration.historyTable}' does not exist.")
                    appendLine("please run: ./application.kexe migrate up")
                    if (result.pendingCount > 0) {
                        appendLine("pending scripts: ${result.pendingCount}")
                        result.scripts.take(10).forEach {
                            appendLine("  [${it.moduleId}] V${it.version}  ${it.description}")
                        }
                        if (result.scripts.size > 10) appendLine("  ... ${result.scripts.size - 10} more")
                    }
                }

                // FAILED rows — manual intervention
                result.failedCount > 0 -> buildString {
                    appendLine("migration history has ${result.failedCount} FAILED row(s); manual intervention required:")
                    result.scripts.filter { it.state == MigrationResult.ScriptState.FAILED }.forEach {
                        appendLine("  [${it.moduleId}] V${it.version}  err=${it.errorMessage}")
                    }
                    appendLine("see migration spec §6.6 (recovery from FAILED).")
                }

                // checksum mismatch — 磁盘 vs history 不一致
                result.mismatchCount > 0 -> buildString {
                    appendLine("migration history checksum mismatch on ${result.mismatchCount} script(s):")
                    result.scripts.filter { it.state == MigrationResult.ScriptState.CHECKSUM_MISMATCH }.forEach {
                        appendLine(
                            "  [${it.moduleId}] V${it.version}: " +
                                "disk=${it.diskChecksum?.take(8)} history=${it.historyChecksum?.take(8)}"
                        )
                    }
                    appendLine("revert SQL or create a new V*.sql to evolve; never edit applied scripts.")
                }

                // pending — 有未应用脚本
                result.pendingCount > 0 -> buildString {
                    appendLine("${result.pendingCount} pending migration(s) detected. please run:")
                    appendLine("  ./application.kexe migrate up")
                    result.scripts.filter { it.state == MigrationResult.ScriptState.PENDING }.take(10).forEach {
                        appendLine("  [${it.moduleId}] V${it.version}  ${it.description}")
                    }
                }

                else -> null // OK
            }

            // STATUS 命令不应返回 Up / Verify, 安全起见兜底
            else -> "migration precheck unexpected result type: $result"
            }
        } finally {
            runBlocking { SqlxDatabase.close() }
        }
    }
}
