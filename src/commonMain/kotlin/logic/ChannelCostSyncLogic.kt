package logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import model.Channel
import model.ChannelModelCost
import model.ModelPrice
import model.PriceHistory
import neton.database.dsl.*
import neton.logging.Logger
import table.ChannelModelCostTable
import table.ChannelTable
import table.ModelPriceTable
import table.PriceHistoryTable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Serializable
data class ChannelCostSyncResult(val channelId: Long, val updated: Int, val pending: Int, val stale: Int, val error: String? = null)

@Serializable
data class ChannelCostListVO(val costs: List<ChannelModelCost>, val pending: List<PriceHistory>)

@Serializable
data class ManualCostRequest(
    val channelId: Long,
    val model: String,
    val inputPrice: String,
    val outputPrice: String,
    val cacheReadPrice: String = "0",
    val cacheWritePrice: String = "0",
    val perRequestPrice: Long? = null,
    val tiers: String? = null,
)

/**
 * 渠道成本同步（design §4.4）：拉上游价目接口 → 换算成我们实付的价 → 写入渠道成本表。
 *
 * - 正常变动自动写入（上游成本就是我们付出去的钱，以上游为准）；
 * - 单次涨或跌超过 50%、或变成 0：不写，记一条 pending 调价记录等运营确认（防上游接口出错整片误判亏本）；
 * - 上游不再报的模型：保留旧值、标 stale，不删；
 * - 拉取失败：成本表不动，记 cost_sync_error；
 * - 手填（source = manual）的行同步时不覆盖，手填优先。
 */
@neton.core.annotations.Logic(logger = "logic.gateway-channel-cost-sync")
class ChannelCostSyncLogic(private val log: Logger, private val channelLogic: ChannelLogic) {

    enum class Decision { APPLY, PENDING }

    suspend fun syncAll(): List<ChannelCostSyncResult> =
        ChannelTable.findAll().filter { it.costSource == "upstream" }.map { syncChannel(it.id, null) }

    @OptIn(ExperimentalTime::class)
    suspend fun syncChannel(channelId: Long, operatorId: Long?): ChannelCostSyncResult {
        val ch = channelLogic.get(channelId) ?: return ChannelCostSyncResult(channelId, 0, 0, 0, "channel not found")
        val format = ch.upstreamPricingFormat
        if (ch.costSource != "upstream" || format == null) return ChannelCostSyncResult(channelId, 0, 0, 0, "channel cost source is not upstream")
        val now = Clock.System.now().toEpochMilliseconds()
        val fetched = fetch(ch, format)
        val list = fetched.getOrElse { e ->
            val msg = (e.message ?: e::class.simpleName ?: "error").take(500)
            channelLogic.update(ch.copy(costSyncError = msg))
            log.warn("channel cost sync failed channel=${ch.id}: $msg")
            return ChannelCostSyncResult(channelId, 0, 0, 0, msg)
        }
        val official = ModelPriceTable.findAll().associateBy { it.model }
        val existing = ChannelModelCostTable.query { where { ChannelModelCost::channelId eq ch.id } }.list().associateBy { it.model }
        val pendingAlready = PriceHistoryTable.query {
            where { and(PriceHistory::channelId eq ch.id, PriceHistory::status eq STATUS_PENDING) }
        }.list().map { it.model to it.after }.toSet()
        var updated = 0; var pending = 0
        val seen = mutableSetOf<String>()
        for (raw in list) {
            val scaled = UpstreamPricingParser.scale(raw, ch.upstreamGroupRatio, ch.upstreamCreditRate)
            for (model in requestModelsFor(ch, raw.upstreamModel)) {
                seen += model
                val old = existing[model]
                if (old?.source == SOURCE_MANUAL) continue
                val next = ModelPrice(
                    model = model, inputPrice = scaled.input, outputPrice = scaled.output,
                    cacheReadPrice = scaled.cacheRead, cacheWritePrice = scaled.cacheWrite, perRequestPrice = scaled.perRequest,
                    tiers = UpstreamPricingParser.withOfficialTiers(scaled, official[model])?.let { PricingTiers.render(it) },
                )
                if (old != null && sameCost(old, next)) {
                    if (old.stale == 1) ChannelModelCostTable.update(old.copy(stale = 0))
                    continue
                }
                when (decide(old, next)) {
                    Decision.APPLY -> {
                        upsert(ch.id, model, next, SOURCE_UPSTREAM, now)
                        record(ch.id, model, old?.toModelPrice(model), next, STATUS_APPLIED, operatorId)
                        updated++
                    }
                    Decision.PENDING -> {
                        val after = PriceHistoryLogic.snapshot(next)
                        if ((model to after) !in pendingAlready) {
                            record(ch.id, model, old?.toModelPrice(model), next, STATUS_PENDING, operatorId)
                            pending++
                        }
                    }
                }
            }
        }
        var stale = 0
        existing.values.filter { it.source == SOURCE_UPSTREAM && it.model !in seen && it.stale == 0 }.forEach {
            ChannelModelCostTable.update(it.copy(stale = 1)); stale++
        }
        channelLogic.update(ch.copy(costSyncedAt = now, costSyncError = null))
        log.info("channel cost sync channel=${ch.id} updated=$updated pending=$pending stale=$stale")
        return ChannelCostSyncResult(channelId, updated, pending, stale)
    }

    suspend fun list(channelId: Long): ChannelCostListVO = ChannelCostListVO(
        costs = ChannelModelCostTable.query { where { ChannelModelCost::channelId eq channelId }; orderBy(ChannelModelCost::model.asc()) }.list(),
        pending = PriceHistoryTable.query {
            where { and(PriceHistory::channelId eq channelId, PriceHistory::status eq STATUS_PENDING) }
            orderBy(PriceHistory::id.desc())
        }.list(),
    )

    @OptIn(ExperimentalTime::class)
    suspend fun saveManual(r: ManualCostRequest, operatorId: Long?) {
        val ch = channelLogic.get(r.channelId) ?: rejectField("channelId", GatewayErrorCodes.CHANNEL_NOT_FOUND)
        val model = r.model.trim().takeIf { it.isNotEmpty() } ?: rejectField("model", GatewayErrorCodes.PRICE_INVALID)
        val next = PricingTiers.withBaseFromTiers(ModelPrice(
            model = model,
            inputPrice = PricingCalc.requirePrice(r.inputPrice, "inputPrice"), outputPrice = PricingCalc.requirePrice(r.outputPrice, "outputPrice"),
            cacheReadPrice = PricingCalc.requirePrice(r.cacheReadPrice, "cacheReadPrice"), cacheWritePrice = PricingCalc.requirePrice(r.cacheWritePrice, "cacheWritePrice"),
            perRequestPrice = r.perRequestPrice?.takeIf { it >= 0 }, tiers = PricingTiers.normalize(r.tiers),
        ))
        val old = ChannelModelCostTable.oneWhere { and(ChannelModelCost::channelId eq ch.id, ChannelModelCost::model eq model) }
        upsert(ch.id, model, next, SOURCE_MANUAL, Clock.System.now().toEpochMilliseconds())
        record(ch.id, model, old?.toModelPrice(model), next, STATUS_APPLIED, operatorId)
    }

    suspend fun delete(channelId: Long, model: String, operatorId: Long?) {
        val old = ChannelModelCostTable.oneWhere { and(ChannelModelCost::channelId eq channelId, ChannelModelCost::model eq model) } ?: return
        ChannelModelCostTable.destroy(old.id)
        record(channelId, model, old.toModelPrice(model), null, STATUS_APPLIED, operatorId)
    }

    /** 接受一条待确认的变动：按记录里的新价写入成本表。 */
    @OptIn(ExperimentalTime::class)
    suspend fun acceptPending(historyId: Long, operatorId: Long?) {
        val h = PriceHistoryTable.get(historyId)?.takeIf { it.status == STATUS_PENDING && it.channelId != null }
            ?: rejectField("id", GatewayErrorCodes.PRICE_NOT_FOUND)
        val next = fromSnapshot(h.model, h.after ?: rejectField("id", GatewayErrorCodes.PRICE_NOT_FOUND))
        upsert(h.channelId!!, h.model, next, SOURCE_UPSTREAM, Clock.System.now().toEpochMilliseconds())
        PriceHistoryTable.update(h.copy(status = STATUS_APPLIED, operatorId = operatorId ?: h.operatorId))
    }

    suspend fun ignorePending(historyId: Long, operatorId: Long?) {
        val h = PriceHistoryTable.get(historyId)?.takeIf { it.status == STATUS_PENDING } ?: rejectField("id", GatewayErrorCodes.PRICE_NOT_FOUND)
        PriceHistoryTable.update(h.copy(status = STATUS_IGNORED, operatorId = operatorId ?: h.operatorId))
    }

    private suspend fun fetch(ch: Channel, format: String): Result<List<UpstreamPrice>> {
        val provider = RelayEngine.deployConfigClients ?: return Result.failure(IllegalStateException("HttpClientProvider 未绑定"))
        val url = ch.upstreamPricingUrl ?: defaultPricingUrl(ch.baseUrl, format)
        val client = provider.create { connectMillis = 5_000; requestMillis = 30_000; socketMillis = 30_000 }
        return try {
            // OpenRouter 的 /models 要带渠道 Key；new-api 的 /api/pricing 是公开的
            val headers = if (format == "openrouter") channelLogic.pickKey(ch.id)?.let {
                neton.core.http.HttpHeaders.of("Authorization" to "Bearer ${it.apiKey}")
            } else null
            val res = client.request(neton.http.client.HttpClientRequest(
                method = neton.http.client.HttpClientMethod.Get, url = url,
                headers = headers ?: neton.core.http.HttpHeaders.EMPTY,
            ))
            if (res.statusCode !in 200..299) Result.failure(IllegalStateException("上游价目接口 HTTP ${res.statusCode}"))
            else UpstreamPricingParser.parse(format, res.body)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("上游价目接口不可达：${e.message}"))
        } finally {
            runCatching { client.close() }
        }
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun upsert(channelId: Long, model: String, p: ModelPrice, source: String, now: Long) {
        val row = ChannelModelCost(
            channelId = channelId, model = model,
            inputPrice = p.inputPrice, outputPrice = p.outputPrice, cacheReadPrice = p.cacheReadPrice, cacheWritePrice = p.cacheWritePrice,
            perRequestPrice = p.perRequestPrice, tiers = p.tiers, source = source, stale = 0, updatedAt = now,
        )
        val old = ChannelModelCostTable.oneWhere { and(ChannelModelCost::channelId eq channelId, ChannelModelCost::model eq model) }
        if (old == null) ChannelModelCostTable.insert(row) else ChannelModelCostTable.update(row.copy(id = old.id))
    }

    private suspend fun record(channelId: Long, model: String, before: ModelPrice?, after: ModelPrice?, status: String, operatorId: Long?) {
        runCatching {
            PriceHistoryTable.insert(PriceHistory(
                model = model, action = ACTION, before = before?.let { PriceHistoryLogic.snapshot(it) },
                after = after?.let { PriceHistoryLogic.snapshot(it) }, operatorId = operatorId, channelId = channelId, status = status,
            ))
        }.onFailure { log.warn("channel cost history not recorded: ${it.message}") }
    }

    private fun sameCost(old: ChannelModelCost, next: ModelPrice): Boolean =
        PriceHistoryLogic.snapshot(old.toModelPrice(next.model)) == PriceHistoryLogic.snapshot(next)

    private fun fromSnapshot(model: String, snapshot: String): ModelPrice {
        val o = Json.parseToJsonElement(snapshot).jsonObject
        fun s(k: String) = o[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull ?: "0"
        return ModelPrice(
            model = model, inputPrice = s("input"), outputPrice = s("output"), cacheReadPrice = s("cacheRead"), cacheWritePrice = s("cacheWrite"),
            perRequestPrice = o["perRequest"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull,
            tiers = o["tiers"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull,
        )
    }

    companion object {
        const val ACTION = "channel_cost"
        const val SOURCE_UPSTREAM = "upstream"
        const val SOURCE_MANUAL = "manual"
        const val STATUS_APPLIED = "applied"
        const val STATUS_PENDING = "pending"
        const val STATUS_IGNORED = "ignored"

        /** 单次变动超过这个比例（万分位）先挂起。 */
        const val SUSPICIOUS_CHANGE_BP = 5_000L

        /**
         * 异常变动判定：任一项旧值 > 0 且涨跌超过 50%，或任一项从非 0 变成 0、或新价全为 0 → PENDING。
         * 首次（没有旧值）只有全 0 挂起。
         */
        fun decide(old: ChannelModelCost?, new: ModelPrice): Decision {
            val n = listOf(new.inputPrice, new.outputPrice, new.cacheReadPrice, new.cacheWritePrice).map { PricingCalc.parseMicro(it) }
            val newPer = new.perRequestPrice
            if (n.all { it == 0L } && (newPer == null || newPer == 0L)) return Decision.PENDING
            if (old == null) return Decision.APPLY
            val o = listOf(old.inputPrice, old.outputPrice, old.cacheReadPrice, old.cacheWritePrice).map { PricingCalc.parseMicro(it) }
            val pairs = o.zip(n) + listOfNotNull(old.perRequestPrice?.let { it to (newPer ?: 0L) })
            for ((a, b) in pairs) {
                if (a == 0L) continue
                if (b == 0L) return Decision.PENDING
                if (kotlin.math.abs(b - a) * 10_000 / a > SUSPICIOUS_CHANGE_BP) return Decision.PENDING
            }
            return Decision.APPLY
        }

        private val json = Json { ignoreUnknownKeys = true }

        /** 上游模型名 → 本渠道的请求模型名：model_mapping 里值等于它的所有键，加上渠道 models 里同名的。 */
        fun requestModelsFor(ch: Channel, upstreamModel: String): Set<String> {
            val mapped = ch.modelMapping?.takeIf { it.isNotBlank() }
                ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
                ?.filter { (_, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == upstreamModel }?.keys.orEmpty()
            val listed = ch.models.split(',').map { it.trim() }.filter { it == upstreamModel }
            return mapped + listed
        }

        /** 没填地址时按格式推断：new-api 去掉末尾 /v1 拼 /api/pricing；OpenRouter 以 /v1 结尾拼 /models，否则拼 /v1/models。 */
        fun defaultPricingUrl(baseUrl: String, format: String): String {
            val base = baseUrl.trim().trimEnd('/')
            return when (format) {
                "openrouter" -> if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
                else -> "${base.removeSuffix("/v1")}/api/pricing"
            }
        }
    }
}
