package controller.gateway

import neton.core.annotations.*
import neton.core.http.HttpContext
import neton.core.http.HttpResponse
import neton.core.http.HttpStatus

/**
 * Prometheus 文本指标。数字从库里现算（日志表按 created_at 有索引，一次抓取几条聚合查询），
 * 不在进程里攒计数器 —— 多实例部署时进程内计数各说各话，而库里的口径与概览页一致。
 * 默认匿名；设了 NEWGATE_METRICS_TOKEN 就要求 `Authorization: Bearer <token>`。
 */
@Controller("/metrics")
@AllowAnonymous
class MetricsController {
    @Get("")
    suspend fun metrics(ctx: HttpContext, response: HttpResponse) {
        val token = neton.core.config.getEnv("NEWGATE_METRICS_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }
        if (token != null && ctx.request.header("Authorization") != "Bearer $token") {
            response.status = HttpStatus.UNAUTHORIZED
            response.text("unauthorized\n"); return
        }
        val db = neton.database.dbContext()
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val sb = StringBuilder()
        fun line(name: String, value: Any, labels: String = "") { sb.append(name).append(labels).append(' ').append(value).append('\n') }
        val byStatus = db.fetchAll(
            "SELECT status, COUNT(*) AS n, COALESCE(SUM(charged),0)::BIGINT AS ch, COALESCE(SUM(cost),0)::BIGINT AS co FROM gateway_usage_logs GROUP BY status", emptyMap())
        sb.append("# HELP netonapi_requests_total Relay requests recorded in usage logs.\n# TYPE netonapi_requests_total counter\n")
        for (r in byStatus) line("netonapi_requests_total", r.longOrNull("n") ?: 0, "{status=\"${r.stringOrNull("status")}\"}")
        sb.append("# TYPE netonapi_charged_micro_total counter\n")
        line("netonapi_charged_micro_total", byStatus.sumOf { it.longOrNull("ch") ?: 0 })
        sb.append("# TYPE netonapi_cost_micro_total counter\n")
        line("netonapi_cost_micro_total", byStatus.sumOf { it.longOrNull("co") ?: 0 })
        val recent = db.fetchAll(
            "SELECT status, COUNT(*) AS n, COALESCE(AVG(latency_ms),0)::BIGINT AS lat FROM gateway_usage_logs WHERE created_at >= :from GROUP BY status", mapOf("from" to now - 300_000))
        sb.append("# HELP netonapi_requests_5m Requests in the last five minutes.\n# TYPE netonapi_requests_5m gauge\n")
        for (r in recent) {
            line("netonapi_requests_5m", r.longOrNull("n") ?: 0, "{status=\"${r.stringOrNull("status")}\"}")
            line("netonapi_latency_ms_avg_5m", r.longOrNull("lat") ?: 0, "{status=\"${r.stringOrNull("status")}\"}")
        }
        val channels = db.fetchAll("SELECT id, name, status, last_probe_ms, last_probe_ok FROM gateway_channels WHERE deleted = 0", emptyMap())
        sb.append("# TYPE netonapi_channel_status gauge\n# TYPE netonapi_channel_probe_ms gauge\n# TYPE netonapi_channel_probe_ok gauge\n")
        for (c in channels) {
            val l = "{channel=\"${(c.stringOrNull("name") ?: "").replace("\"", "'")}\",id=\"${c.longOrNull("id")}\"}"
            line("netonapi_channel_status", c.intOrNull("status") ?: 0, l)
            c.longOrNull("last_probe_ms")?.let { line("netonapi_channel_probe_ms", it, l) }
            c.intOrNull("last_probe_ok")?.let { line("netonapi_channel_probe_ok", it, l) }
        }
        val manual = db.fetchAll("SELECT COUNT(*) AS n FROM gateway_settlements WHERE status = 'MANUAL_REVIEW'", emptyMap()).firstOrNull()?.longOrNull("n") ?: 0
        sb.append("# TYPE netonapi_settlements_manual_review gauge\n"); line("netonapi_settlements_manual_review", manual)
        val pending = db.fetchAll("SELECT COUNT(*) AS n FROM gateway_settlements WHERE status IN ('FINALIZE_PENDING','RELEASE_PENDING')", emptyMap()).firstOrNull()?.longOrNull("n") ?: 0
        sb.append("# TYPE netonapi_settlements_pending gauge\n"); line("netonapi_settlements_pending", pending)
        response.contentType = "text/plain; version=0.0.4; charset=utf-8"
        response.text(sb.toString())
    }
}
