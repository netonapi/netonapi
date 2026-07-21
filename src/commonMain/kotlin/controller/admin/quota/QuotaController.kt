package controller.admin.quota

import kotlinx.serialization.Serializable
import logic.QuotaLogic
import model.QuotaAccount
import model.QuotaTransaction
import neton.core.annotations.*

@Serializable
data class GrantQuotaRequest(val userId: Long, val amount: Long, val ref: String? = null)

@Controller("/gateway/quota")
class QuotaController(private val quotaLogic: QuotaLogic) {

    @Get("/get-by-user/{userId}")
    @Permission("gateway:quota:get")
    suspend fun getByUser(@PathVariable userId: Long): QuotaAccount = quotaLogic.account(userId)

    @Post("/grant")
    @Permission("gateway:quota:grant")
    suspend fun grant(@Body request: GrantQuotaRequest): Long =
        quotaLogic.grant(request.userId, request.amount, ref = request.ref)

    @Get("/transactions/{userId}")
    @Permission("gateway:quota:get")
    suspend fun transactions(@PathVariable userId: Long): List<QuotaTransaction> =
        quotaLogic.transactions(userId)
}
