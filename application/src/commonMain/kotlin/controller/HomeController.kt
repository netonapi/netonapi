package controller

import kotlinx.serialization.Serializable
import neton.core.annotations.AllowAnonymous
import neton.core.annotations.Controller
import neton.core.annotations.Get

@Serializable
data class HealthResponse(
    val status: String,
    val service: String,
    val version: String
)

@Controller("/")
class HomeController {

    @Get("/")
    @AllowAnonymous
    suspend fun health(): HealthResponse {
        return HealthResponse(
            status = "ok",
            service = "netonapi",
            version = "1.0.0"
        )
    }
}
