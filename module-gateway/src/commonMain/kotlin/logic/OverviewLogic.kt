package logic

import neton.database.dsl.*
import kotlinx.serialization.Serializable
import neton.logging.Logger

@Serializable
data class PeriodStat(
    val requests: Long = 0,
    val errors: Long = 0,
    /** μUSD */
    val charged: Long = 0,
    val cost: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val activeUsers: Long = 0,
)

@Serializable
data class DailyStat(val date: String, val requests: Long, val errors: Long, val charged: Long, val cost: Long)

@Serializable
data class ModelStat(val model: String, val requests: Long, val charged: Long, val cost: Long)

@Serializable
data class UserStat(val userId: Long, val requests: Long, val charged: Long, val cost: Long = 0)

/** 按计费分组的收入与成本：看 VIP 折扣到底让出去了多少毛利。 */
@Serializable
data class GroupStat(val groupCode: String, val requests: Long, val charged: Long, val cost: Long)

@Serializable
data class ChannelStat(
    val channelId: Long, val name: String, val status: Int, val requests: Long, val errors: Long, val cost: Long,
    /** 走这条渠道的请求向用户收了多少（与 cost 对照就是这条渠道的毛利）。 */
    val charged: Long = 0,
    val lastProbeAt: Long? = null, val lastProbeMs: Long? = null, val lastProbeOk: Int? = null, val lastProbeError: String? = null,
)

@Serializable
data class OverviewVO(
    val today: PeriodStat,
    val period: PeriodStat,
    val days: Int,
    val daily: List<DailyStat>,
    val topModels: List<ModelStat>,
    val topUsers: List<UserStat>,
    val channels: List<ChannelStat>,
    val groups: List<GroupStat> = emptyList(),
    /** 统计期内实收低于成本的请求笔数与亏掉的金额（μUSD）。不为 0 就该去「模型定价」看体检结果。 */
    val lossRequests: Long = 0,
    val lossAmount: Long = 0,
    /** 最近一次价源检查（定时任务 gateway-price-sync）：官方价有变化或可新增的模型要运营确认。 */
    val priceSync: model.PriceSyncRun? = null,
    val manualReviewSettlements: Long,
    val suspendedChannels: Long,
    val generatedAt: Long,
)

/**
 * 管理台概览：全部从 gateway_usage_logs / gateway_settlements / gateway_channels 现算，不建汇总表。
 *
 * 为什么现算：这一页是运营早上打开看一眼的东西，一天几十次查询；usage_logs 按 created_at 有索引，
 * 七天窗口的聚合在百万行量级仍是亚秒。等到它慢了再建日汇总表也不迟 —— 现在建等于多一条要对账的路径。
 * 日期按 UTC 切，与日志里的时间戳一致；界面上标明。
 */
// SUM(BIGINT) 在 PostgreSQL 里是 NUMERIC，而 sqlx4k 解不了 NUMERIC 会直接 abort 整个进程（定价列当年改成
// VARCHAR 就是为了绕它）。所以每个 SUM 都显式 ::BIGINT —— 日志表里的金额本来就是整数 μUSD，不会丢精度。
@neton.core.annotations.Logic(logger = "logic.gateway-overview")
class OverviewLogic(private val log: Logger) {

    suspend fun overview(days: Int): OverviewVO {
        val d = days.coerceIn(1, 90)
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val dayMs = 86_400_000L
        val todayStart = now / dayMs * dayMs
        val periodStart = todayStart - (d - 1) * dayMs
        val db = neton.database.dbContext()

        suspend fun period(from: Long): PeriodStat {
            val r = db.fetchAll(
                """SELECT COUNT(*) AS n, COUNT(*) FILTER (WHERE status <> 'ok') AS e,
                          COALESCE(SUM(charged),0)::BIGINT AS ch, COALESCE(SUM(cost),0)::BIGINT AS co,
                          COALESCE(SUM(prompt_tokens),0)::BIGINT AS pt, COALESCE(SUM(completion_tokens),0)::BIGINT AS ct,
                          COUNT(DISTINCT user_id) AS u
                     FROM gateway_usage_logs WHERE created_at >= :from""",
                mapOf("from" to from),
            ).firstOrNull() ?: return PeriodStat()
            return PeriodStat(
                requests = r.longOrNull("n") ?: 0, errors = r.longOrNull("e") ?: 0,
                charged = r.longOrNull("ch") ?: 0, cost = r.longOrNull("co") ?: 0,
                promptTokens = r.longOrNull("pt") ?: 0, completionTokens = r.longOrNull("ct") ?: 0,
                activeUsers = r.longOrNull("u") ?: 0,
            )
        }

        val daily = db.fetchAll(
            """SELECT to_char(to_timestamp((created_at / 86400000) * 86400) AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day,
                      COUNT(*) AS n, COUNT(*) FILTER (WHERE status <> 'ok') AS e,
                      COALESCE(SUM(charged),0)::BIGINT AS ch, COALESCE(SUM(cost),0)::BIGINT AS co
                 FROM gateway_usage_logs WHERE created_at >= :from
                GROUP BY created_at / 86400000 ORDER BY created_at / 86400000""",
            mapOf("from" to periodStart),
        ).map { DailyStat(it.stringOrNull("day") ?: "", it.longOrNull("n") ?: 0, it.longOrNull("e") ?: 0, it.longOrNull("ch") ?: 0, it.longOrNull("co") ?: 0) }

        val topModels = db.fetchAll(
            """SELECT request_model AS m, COUNT(*) AS n, COALESCE(SUM(charged),0)::BIGINT AS ch, COALESCE(SUM(cost),0)::BIGINT AS co
                 FROM gateway_usage_logs WHERE created_at >= :from
                GROUP BY request_model ORDER BY n DESC LIMIT 10""",
            mapOf("from" to periodStart),
        ).map { ModelStat(it.stringOrNull("m") ?: "", it.longOrNull("n") ?: 0, it.longOrNull("ch") ?: 0, it.longOrNull("co") ?: 0) }

        val topUsers = db.fetchAll(
            """SELECT user_id AS u, COUNT(*) AS n, COALESCE(SUM(charged),0)::BIGINT AS ch, COALESCE(SUM(cost),0)::BIGINT AS co
                 FROM gateway_usage_logs WHERE created_at >= :from
                GROUP BY user_id ORDER BY ch DESC LIMIT 10""",
            mapOf("from" to periodStart),
        ).map { UserStat(it.longOrNull("u") ?: 0, it.longOrNull("n") ?: 0, it.longOrNull("ch") ?: 0, it.longOrNull("co") ?: 0) }

        val groups = db.fetchAll(
            """SELECT COALESCE(group_code,'') AS g, COUNT(*) AS n, COALESCE(SUM(charged),0)::BIGINT AS ch, COALESCE(SUM(cost),0)::BIGINT AS co
                 FROM gateway_usage_logs WHERE created_at >= :from
                GROUP BY group_code ORDER BY ch DESC""",
            mapOf("from" to periodStart),
        ).map { GroupStat(it.stringOrNull("g") ?: "", it.longOrNull("n") ?: 0, it.longOrNull("ch") ?: 0, it.longOrNull("co") ?: 0) }

        val loss = db.fetchAll(
            """SELECT COUNT(*) AS n, COALESCE(SUM(cost - charged),0)::BIGINT AS amt
                 FROM gateway_usage_logs WHERE created_at >= :from AND cost > charged""",
            mapOf("from" to periodStart),
        ).firstOrNull()

        val channels = db.fetchAll(
            """SELECT c.id AS id, c.name AS name, c.status AS st,
                      c.last_probe_at AS pat, c.last_probe_ms AS pms, c.last_probe_ok AS pok, c.last_probe_error AS perr,
                      COUNT(l.id) AS n, COUNT(l.id) FILTER (WHERE l.status <> 'ok') AS e, COALESCE(SUM(l.cost),0)::BIGINT AS co,
                      COALESCE(SUM(l.charged),0)::BIGINT AS ch
                 FROM gateway_channels c
                 LEFT JOIN gateway_usage_logs l ON l.channel_id = c.id AND l.created_at >= :from
                WHERE c.deleted = 0
                GROUP BY c.id, c.name, c.status, c.last_probe_at, c.last_probe_ms, c.last_probe_ok, c.last_probe_error ORDER BY n DESC, c.id""",
            mapOf("from" to periodStart),
        ).map { ChannelStat(it.longOrNull("id") ?: 0, it.stringOrNull("name") ?: "", it.intOrNull("st") ?: 0, it.longOrNull("n") ?: 0, it.longOrNull("e") ?: 0, it.longOrNull("co") ?: 0,
            charged = it.longOrNull("ch") ?: 0,
            lastProbeAt = it.longOrNull("pat"), lastProbeMs = it.longOrNull("pms"), lastProbeOk = it.intOrNull("pok"), lastProbeError = it.stringOrNull("perr")) }

        val manual = db.fetchAll("SELECT COUNT(*) AS n FROM gateway_settlements WHERE status = 'MANUAL_REVIEW'", emptyMap())
            .firstOrNull()?.longOrNull("n") ?: 0
        val suspended = db.fetchAll("SELECT COUNT(*) AS n FROM gateway_channels WHERE deleted = 0 AND status = 2", emptyMap())
            .firstOrNull()?.longOrNull("n") ?: 0

        return OverviewVO(
            today = period(todayStart), period = period(periodStart), days = d, daily = daily,
            topModels = topModels, topUsers = topUsers, channels = channels, groups = groups,
            lossRequests = loss?.longOrNull("n") ?: 0, lossAmount = loss?.longOrNull("amt") ?: 0,
            priceSync = table.PriceSyncRunTable.query { orderBy(model.PriceSyncRun::id.desc()); limitOffset(1, 0) }.list().firstOrNull(),
            manualReviewSettlements = manual, suspendedChannels = suspended, generatedAt = now,
        )
    }
}
