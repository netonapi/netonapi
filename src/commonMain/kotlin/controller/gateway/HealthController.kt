package controller.gateway

import kotlinx.coroutines.withTimeoutOrNull
import neton.core.annotations.AllowAnonymous
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.http.HttpResponse
import neton.core.http.HttpStatus
import neton.logging.Logger

/**
 * 存活 / 就绪探针。匿名——gateway 路由组其余路由都要 `Bearer sk-`，探针不能要凭证。
 *
 * 200 = 进程在应答且数据库能回 `SELECT 1`；503 = 数据库不可达。每个中转请求都要落库
 * （预留、结算），DB 挂了就不该再接流量，所以就绪判定绑在 DB 上。Redis 只服务限流且可退化为
 * 进程内计数，不纳入探测。容器 HEALTHCHECK、compose 的 depends_on、反代的上游健康检查都用它。
 */
@Controller("/health")
@AllowAnonymous
class HealthController(private val log: Logger) {

    @Get("")
    suspend fun health(response: HttpResponse) {
        val dbOk = withTimeoutOrNull(2_000) {
            runCatching { neton.database.dbContext().fetchAll("SELECT 1", emptyMap()); true }.getOrElse { false }
        } ?: false
        if (dbOk) {
            response.json("""{"status":"ok","db":"ok"}""")
        } else {
            log.warn("health: database unreachable")
            response.status = HttpStatus.fromCode(503) ?: HttpStatus.BAD_GATEWAY
            response.json("""{"status":"degraded","db":"unreachable"}""")
        }
    }
}
