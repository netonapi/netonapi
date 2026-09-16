package logic

import kotlinx.serialization.Serializable
import neton.logging.Logger

@Serializable
data class MyPeriodStat(val requests: Long = 0, val errors: Long = 0, val charged: Long = 0, val promptTokens: Long = 0, val completionTokens: Long = 0)

@Serializable
data class MyDailyStat(val date: String, val requests: Long, val charged: Long)

@Serializable
data class MyModelStat(val model: String, val requests: Long, val charged: Long, val promptTokens: Long, val completionTokens: Long)

@Serializable
data class MyTokenStat(val tokenId: Long, val name: String, val requests: Long, val charged: Long)

@Serializable
data class MyUsageStatsVO(
    val today: MyPeriodStat,
    val period: MyPeriodStat,
    val days: Int,
    val daily: List<MyDailyStat>,
    val byModel: List<MyModelStat>,
    val byToken: List<MyTokenStat>,
)

/**
 * 用户自己的用量汇总。口径与管理台概览一致（同一张日志表、同样按 UTC 日切、SUM 一律 ::BIGINT），
 * 只是多了一道 user_id 过滤 —— 用户看到的「今天花了多少」必须和运营那边对得上。
 */
@neton.core.annotations.Logic(logger = "logic.gateway-usage-stats")
class UsageStatsLogic(private val log: Logger) {

    suspend fun forUser(userId: Long, days: Int): MyUsageStatsVO {
        val d = days.coerceIn(1, 90)
        val dayMs = 86_400_000L
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val todayStart = now / dayMs * dayMs
        val periodStart = todayStart - (d - 1) * dayMs
        val db = neton.database.dbContext()

        suspend fun period(from: Long): MyPeriodStat {
            val r = db.fetchAll(
                """SELECT COUNT(*) AS n, COUNT(*) FILTER (WHERE status <> 'ok') AS e, COALESCE(SUM(charged),0)::BIGINT AS ch,
                          COALESCE(SUM(prompt_tokens),0)::BIGINT AS pt, COALESCE(SUM(completion_tokens),0)::BIGINT AS ct
                     FROM gateway_usage_logs WHERE user_id = :uid AND created_at >= :from""",
                mapOf("uid" to userId, "from" to from),
            ).firstOrNull() ?: return MyPeriodStat()
            return MyPeriodStat(r.longOrNull("n") ?: 0, r.longOrNull("e") ?: 0, r.longOrNull("ch") ?: 0, r.longOrNull("pt") ?: 0, r.longOrNull("ct") ?: 0)
        }

        val daily = db.fetchAll(
            """SELECT to_char(to_timestamp((created_at / 86400000) * 86400) AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day,
                      COUNT(*) AS n, COALESCE(SUM(charged),0)::BIGINT AS ch
                 FROM gateway_usage_logs WHERE user_id = :uid AND created_at >= :from
                GROUP BY created_at / 86400000 ORDER BY created_at / 86400000""",
            mapOf("uid" to userId, "from" to periodStart),
        ).map { MyDailyStat(it.stringOrNull("day") ?: "", it.longOrNull("n") ?: 0, it.longOrNull("ch") ?: 0) }

        val byModel = db.fetchAll(
            """SELECT request_model AS m, COUNT(*) AS n, COALESCE(SUM(charged),0)::BIGINT AS ch,
                      COALESCE(SUM(prompt_tokens),0)::BIGINT AS pt, COALESCE(SUM(completion_tokens),0)::BIGINT AS ct
                 FROM gateway_usage_logs WHERE user_id = :uid AND created_at >= :from
                GROUP BY request_model ORDER BY ch DESC, n DESC LIMIT 50""",
            mapOf("uid" to userId, "from" to periodStart),
        ).map { MyModelStat(it.stringOrNull("m") ?: "", it.longOrNull("n") ?: 0, it.longOrNull("ch") ?: 0, it.longOrNull("pt") ?: 0, it.longOrNull("ct") ?: 0) }

        val byToken = db.fetchAll(
            """SELECT l.token_id AS tid, COALESCE(t.name, '') AS name, COUNT(*) AS n, COALESCE(SUM(l.charged),0)::BIGINT AS ch
                 FROM gateway_usage_logs l LEFT JOIN gateway_tokens t ON t.id = l.token_id
                WHERE l.user_id = :uid AND l.created_at >= :from
                GROUP BY l.token_id, t.name ORDER BY ch DESC LIMIT 50""",
            mapOf("uid" to userId, "from" to periodStart),
        ).map { MyTokenStat(it.longOrNull("tid") ?: 0, it.stringOrNull("name") ?: "", it.longOrNull("n") ?: 0, it.longOrNull("ch") ?: 0) }

        return MyUsageStatsVO(today = period(todayStart), period = period(periodStart), days = d, daily = daily, byModel = byModel, byToken = byToken)
    }
}
