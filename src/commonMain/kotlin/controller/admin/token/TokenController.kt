package controller.admin.token

import kotlinx.serialization.Serializable
import logic.IssuedToken
import logic.TokenLogic
import model.GatewayToken
import neton.core.annotations.*

@Serializable
data class CreateTokenRequest(
    val userId: Long,
    val name: String,
    val quotaBudget: Long? = null,
    val expiresAt: Long? = null,
    val allowedModels: String = "",
    val allowedIps: String = "",
    val groupOverride: String? = null,
)

@Serializable
data class IssuedTokenVO(val id: Long, val key: String, val display: String)

/** 对外视图：不含 keyHash。 */
@Serializable
data class TokenVO(
    val id: Long,
    val userId: Long,
    val name: String,
    val display: String,
    val quotaBudget: Long? = null,
    val quotaUsed: Long = 0,
    val expiresAt: Long? = null,
    val allowedModels: String = "",
    val allowedIps: String = "",
    val groupOverride: String? = null,
    val status: Int = 1,
    val createdAt: Long? = null,
) {
    companion object {
        fun from(t: GatewayToken) = TokenVO(
            id = t.id, userId = t.userId, name = t.name, display = t.keyDisplay,
            quotaBudget = t.quotaBudget, quotaUsed = t.quotaUsed, expiresAt = t.expiresAt,
            allowedModels = t.allowedModels, allowedIps = t.allowedIps,
            groupOverride = t.groupOverride, status = t.status, createdAt = t.createdAt,
        )
    }
}

@Controller("/gateway/token")
class TokenController(private val tokenLogic: TokenLogic) {

    @Post("/create")
    @Permission("gateway:token:create")
    suspend fun create(@Body request: CreateTokenRequest): IssuedTokenVO {
        val issued: IssuedToken = tokenLogic.issue(
            userId = request.userId, name = request.name,
            quotaBudget = request.quotaBudget, expiresAt = request.expiresAt,
            allowedModels = request.allowedModels, allowedIps = request.allowedIps,
            groupOverride = request.groupOverride,
        )
        // 明文仅在创建响应中出现一次
        return IssuedTokenVO(issued.record.id, issued.plaintext, issued.record.keyDisplay)
    }

    @Get("/list-by-user/{userId}")
    @Permission("gateway:token:list")
    suspend fun listByUser(@PathVariable userId: Long): List<TokenVO> =
        tokenLogic.listByUser(userId).map { TokenVO.from(it) }

    @Put("/update-status")
    @Permission("gateway:token:update")
    suspend fun updateStatus(@Query id: Long, @Query status: Int) =
        tokenLogic.updateStatus(id, status)

    @Delete("/delete/{id}")
    @Permission("gateway:token:delete")
    suspend fun delete(@PathVariable id: Long) = tokenLogic.delete(id)
}
