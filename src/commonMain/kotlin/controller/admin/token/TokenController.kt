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
    /** 1 = 分组是运营指定的；0 = 用户自己选的 / 没绑。 */
    val groupAssigned: Int = 0,
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
            groupOverride = t.groupOverride, groupAssigned = t.groupAssigned,
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

    /** 运营指定这把 Key 的分组（特价 / 专线，用户改不了）；groupCode 留空 = 取消指定，Key 回到 default。 */
    @Put("/update-group")
    @Permission("gateway:token:update")
    suspend fun updateGroup(@Query id: Long, @Query groupCode: String? = null) =
        tokenLogic.updateGroup(id, groupCode)

    @Delete("/delete/{id}")
    @Permission("gateway:token:delete")
    suspend fun delete(@PathVariable id: Long) = tokenLogic.delete(id)
}
