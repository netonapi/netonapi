package job

import neton.core.component.NetonContext
import neton.jobs.Job
import neton.jobs.JobContext
import neton.jobs.JobExecutor
import neton.logging.Logger

/** 渠道定时测速：每 5 分钟探测全部启用渠道，结果写在渠道行上（V010）。单节点跑就够，多节点同时探是浪费。 */
@Job(id = "gateway-channel-probe", cron = "*/5 * * * *", lockTtlMs = 240_000)
class ChannelProbeJob(private val log: Logger, private val ctx: NetonContext) : JobExecutor {
    override suspend fun run(context: JobContext) {
        val probe = ctx.getOrNull(logic.ChannelProbeLogic::class) ?: return
        val n = probe.probeAllEnabled()
        log.info("gateway.channel.probe channels=$n")
    }
}

/** 价源同步：每天 03:10 从 NETONAPI_PRICE_SYNC_URL 拉一次价目表；没配就什么都不做。 */
@Job(id = "gateway-price-sync", cron = "10 3 * * *", lockTtlMs = 300_000)
class PriceSyncJob(private val log: Logger, private val ctx: NetonContext) : JobExecutor {
    override suspend fun run(context: JobContext) {
        val importer = ctx.getOrNull(logic.PriceImportLogic::class) ?: return
        val r = importer.syncFromEnv() ?: return
        if (r.errors.isNotEmpty()) log.error("gateway.price.sync failed: ${r.errors.joinToString("; ").take(500)}")
        else log.info("gateway.price.sync created=${r.created} updated=${r.updated} skipped=${r.skipped}")
    }
}

/**
 * 能力验证：每 6 小时对 Gemini 渠道的每个模型做一次「带官方占位签名的两轮工具调用」探测，
 * 结果写入 V016 验证记录。占位兼容只在有效的通过记录下生效（neton-docs spec/ai.md §4.5.2）。
 */
@Job(id = "gateway-capability-verify", cron = "17 */6 * * *", lockTtlMs = 600_000)
class CapabilityVerifyJob(private val log: Logger, private val ctx: NetonContext) : JobExecutor {
    override suspend fun run(context: JobContext) {
        val verify = ctx.getOrNull(logic.ChannelVerificationLogic::class) ?: return
        val n = verify.verifyAllGeminiSignatureBypass()
        log.info("gateway.capability.verify gemini_signature_bypass targets=$n")
    }
}
