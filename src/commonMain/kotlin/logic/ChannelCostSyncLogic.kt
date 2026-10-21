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

    /** 逐条渠道各自隔离：一条渠道出错（数据库、上游怪数据）只记在它自己身上，不中断后面的渠道。 */
    suspend fun syncAll(): List<ChannelCostSyncResult> =
        ChannelTable.findAll().filter { it.costSource == "upstream" }.map { ch ->
            runCatching { syncChannel(ch.id, null) }.getOrElse { e ->
                val msg = (e.message ?: e::class.simpleName ?: "error").take(500)
                log.error("channel cost sync crashed channel=${ch.id}: $msg")
                runCatching { markSync(ch.id, null, msg) }
                ChannelCostSyncResult(ch.id, 0, 0, 0, msg)
            }
        }

    @OptIn(ExperimentalTime::class)
    suspend fun syncChannel(channelId: Long, operatorId: Long?): ChannelCostSyncResult {
        val ch = channelLogic.get(channelId) ?: return ChannelCostSyncResult(channelId, 0, 0, 0, "channel not found")
        val format = ch.upstreamPricingFormat
        if (ch.costSource != "upstream" || format == null) return ChannelCostSyncResult(channelId, 0, 0, 0, "channel cost source is not upstream")
        val now = Clock.System.now().toEpochMilliseconds()
        val fetched = fetch(ch, format)
        val list = fetched.getOrElse { e ->
            val msg = (e.message ?: e::class.simpleName ?: "error").take(500)
            markSync(ch.id, null, msg)
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
            // 换算后越界（倍率放大到超出价格上限）的条目跳过，不让一条怪数据打断整条渠道
            val scaled = runCatching { UpstreamPricingParser.scale(raw, ch.upstreamGroupRatio, ch.upstreamCreditRate) }
                .getOrElse { log.warn("channel cost sync skipped ${raw.upstreamModel} on channel ${ch.id}: ${it.message}"); continue }
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
        markSync(ch.id, now, null)
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

    /**
     * 经渠道的**受控客户端**拉取（与转发、探测同一条出站路径）：地址是后台运行期数据，每次连接都按运维出站策略
     * 校验解析出的地址，并走渠道配置的代理。写入时的地址检查只是快速反馈，挡不住 DNS 改指向内网。
     * OpenRouter 的 /models 要带渠道 Key，但只发给渠道 Base URL 同一主机，不发给价目地址里写的任意主机。
     */
    private suspend fun fetch(ch: Channel, format: String): Result<List<UpstreamPrice>> {
        val url = ch.upstreamPricingUrl ?: defaultPricingUrl(ch.baseUrl, format)
        return try {
            val headers = if (format == "openrouter" && hostOf(url) == hostOf(ch.baseUrl)) channelLogic.pickKey(ch.id)?.let {
                neton.core.http.HttpHeaders.of("Authorization" to "Bearer ${it.apiKey}")
            } else null
            val request = neton.http.client.HttpClientRequest(
                method = neton.http.client.HttpClientMethod.Get, url = url,
                headers = headers ?: neton.core.http.HttpHeaders.EMPTY,
                timeout = neton.http.client.HttpClientTimeouts(requestMillis = 30_000),
            )
            val res = RelayEngine.withChannelClient(ch) { it.request(request) }
            if (res.statusCode !in 200..299) Result.failure(IllegalStateException("上游价目接口 HTTP ${res.statusCode}"))
            else UpstreamPricingParser.parse(format, res.body)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("上游价目接口不可达：${e.message}"))
        }
    }

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').lowercase()

    /**
     * 只改同步状态两列：拉取可能耗时几十秒，期间运营改了优先级、渠道被自动挂起、测速写了结果 ——
     * 用之前读到的整行写回会把这些改动全部冲掉。成功时 [syncedAt] 非空并清掉错误；失败时只记错误。
     */
    private suspend fun markSync(channelId: Long, syncedAt: Long?, error: String?) {
        val db = neton.database.dbContext()
        if (syncedAt != null) db.execute("UPDATE gateway_channels SET cost_synced_at = :t, cost_sync_error = NULL WHERE id = :id", mapOf("t" to syncedAt, "id" to channelId))
        else db.execute("UPDATE gateway_channels SET cost_sync_error = :e WHERE id = :id", mapOf("e" to error, "id" to channelId))
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
            val mapping = ch.modelMapping?.takeIf { it.isNotBlank() }
                ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            val mapped = mapping?.filter { (_, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == upstreamModel }?.keys.orEmpty()
            // 同名匹配只对没做映射的模型成立：gpt-4o 映射到 gpt-4o-mini 时，它的成本是 mini 的价，不能再拿 gpt-4o 的价
            val listed = ch.models.split(',').map { it.trim() }.filter { it == upstreamModel && (mapping == null || !mapping.containsKey(it)) }
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
