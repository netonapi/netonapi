package controller.app.gateway

import kotlinx.serialization.Serializable
import logic.TokenLogic
import model.GatewayToken
import neton.core.annotations.*
import neton.core.http.ValidationError
import neton.core.http.ValidationException
import neton.core.interfaces.Identity

@Serializable
data class CreateMyTokenRequest(
    val name: String,
    /** μUSD 预算；null = 不限（仍受账户余额约束） */
    val quotaBudget: Long? = null,
    val expiresAt: Long? = null,
    val allowedModels: String = "",
    val allowedIps: String = "",
)

/** 与 [CreateMyTokenRequest] 同一组字段 + id；刻意没有 groupOverride / status / 限流上限。 */
@Serializable
data class UpdateMyTokenRequest(
    val id: Long,
    val name: String,
    val quotaBudget: Long? = null,
    val expiresAt: Long? = null,
    val allowedModels: String = "",
    val allowedIps: String = "",
)

@Serializable
data class MyTokenVO(
    val id: Long,
    val name: String,
    val display: String,
    val quotaBudget: Long? = null,
    val quotaUsed: Long = 0,
    val expiresAt: Long? = null,
    val allowedModels: String = "",
    val allowedIps: String = "",
    val rpmLimit: Int? = null,
    val tpmLimit: Int? = null,
    val concurrencyLimit: Int? = null,
    val status: Int = 1,
    val createdAt: Long? = null,
) {
    companion object {
        fun from(t: GatewayToken) = MyTokenVO(
            id = t.id, name = t.name, display = t.keyDisplay,
            quotaBudget = t.quotaBudget, quotaUsed = t.quotaUsed, expiresAt = t.expiresAt,
            allowedModels = t.allowedModels, allowedIps = t.allowedIps,
            rpmLimit = t.rpmLimit, tpmLimit = t.tpmLimit, concurrencyLimit = t.concurrencyLimit,
            status = t.status, createdAt = t.createdAt,
        )
    }
}

@Serializable
data class IssuedMyTokenVO(val id: Long, val key: String, val display: String)

/**
 * 用户自助管理自己的 API Key。
 *
 * **归属一律取自 identity**，绝不接受请求里传来的 userId——否则就是越权改别人的令牌。
 * 每个按 id 操作的方法都先复核该 id 属于当前用户。
 */
@Controller("/gateway/token")
class MyTokenController(private val tokenLogic: TokenLogic) {

    @Get("/list")
    suspend fun list(identity: Identity): List<MyTokenVO> =
        tokenLogic.listByUser(identity.id.toLong()).map { MyTokenVO.from(it) }

    /** 创建后明文只在本次响应出现一次（后端仅存哈希）。 */
    @Post("/create")
    suspend fun create(identity: Identity, @Body request: CreateMyTokenRequest): IssuedMyTokenVO {
        validate(request.name, request.quotaBudget, request.expiresAt)
        val issued = tokenLogic.issue(
            userId = identity.id.toLong(),
            name = request.name.trim().take(128),
            quotaBudget = request.quotaBudget,
            expiresAt = request.expiresAt,
            allowedModels = request.allowedModels.trim(),
            allowedIps = request.allowedIps.trim(),
            // 限流上限由运营在管理台设定，用户不能自行放宽
        )
        return IssuedMyTokenVO(issued.record.id, issued.plaintext, issued.record.keyDisplay)
    }

    @Put("/update")
    suspend fun update(identity: Identity, @Body request: UpdateMyTokenRequest) {
        requireOwned(identity, request.id)
        validate(request.name, request.quotaBudget, request.expiresAt)
        tokenLogic.updateSelf(
            id = request.id, name = request.name.trim().take(128),
            quotaBudget = request.quotaBudget, expiresAt = request.expiresAt,
            allowedModels = request.allowedModels.trim(), allowedIps = request.allowedIps.trim(),
        )
    }

    private fun validate(name: String, quotaBudget: Long?, expiresAt: Long?) {
        if (name.isBlank()) invalid("name", "name is required")
        quotaBudget?.let { if (it <= 0) invalid("quotaBudget", "must be positive") }
        expiresAt?.let { if (it <= TokenLogic.nowMillis()) invalid("expiresAt", "must be in the future") }
    }

    @Put("/update-status")
    suspend fun updateStatus(identity: Identity, @Query id: Long, @Query status: Int) {
        requireOwned(identity, id)
        if (status != 0 && status != 1) invalid("status", "status must be 0 or 1")
        tokenLogic.updateStatus(id, status)
    }

    @Delete("/delete/{id}")
    suspend fun delete(identity: Identity, @PathVariable id: Long) {
        requireOwned(identity, id)
        tokenLogic.delete(id)
    }

    /** 复核令牌归属；不属于当前用户时按「不存在」处理，不泄露他人令牌是否存在。 */
    private suspend fun requireOwned(identity: Identity, tokenId: Long) {
        val t = tokenLogic.get(tokenId)
        if (t == null || t.userId != identity.id.toLong()) invalid("id", "token not found")
    }

    private fun invalid(field: String, message: String): Nothing =
        throw ValidationException(listOf(ValidationError(path = field, message = message)))
}
