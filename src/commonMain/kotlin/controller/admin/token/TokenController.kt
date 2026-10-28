package controller.admin.token

import kotlinx.serialization.Serializable
import logic.TokenLogic
import model.GatewayToken
import neton.core.annotations.*

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
    val rpmLimit: Int? = null,
    val tpmLimit: Int? = null,
    val concurrencyLimit: Int? = null,
    val status: Int = 1,
    val createdAt: Long? = null,
) {
    companion object {
        fun from(t: GatewayToken) = TokenVO(
            id = t.id, userId = t.userId, name = t.name, display = t.keyDisplay,
            quotaBudget = t.quotaBudget, quotaUsed = t.quotaUsed, expiresAt = t.expiresAt,
            allowedModels = t.allowedModels, allowedIps = t.allowedIps,
            groupOverride = t.groupOverride,
            rpmLimit = t.rpmLimit, tpmLimit = t.tpmLimit, concurrencyLimit = t.concurrencyLimit,
            status = t.status, createdAt = t.createdAt,
        )
    }
}

/**
 * 令牌监管。刻意没有「创建」：Key 只能由用户本人在控制台建（MyTokenController），
 * 调用扣的是他自己的余额，明文也只给他本人看一次；后台代建等于运营替用户造凭据。
 */
@Controller("/gateway/token")
class TokenController(private val tokenLogic: TokenLogic) {

    @Get("/list-by-user/{userId}")
    @Permission("gateway:token:query")
    suspend fun listByUser(@PathVariable userId: Long): List<TokenVO> =
        tokenLogic.listByUser(userId).map { TokenVO.from(it) }

    @Put("/update-status")
    @Permission("gateway:token:update")
    suspend fun updateStatus(@Query id: Long, @Query status: Int) =
        tokenLogic.updateStatus(id, status)

    /** 设置令牌的专属计费组；groupCode 留空 = 取消。 */
    @Put("/update-group")
    @Permission("gateway:token:update")
    suspend fun updateGroup(@Query id: Long, @Query groupCode: String? = null) =
        tokenLogic.updateGroup(id, groupCode)

    @Delete("/delete/{id}")
    @Permission("gateway:token:delete")
    suspend fun delete(@PathVariable id: Long) = tokenLogic.delete(id)
}
