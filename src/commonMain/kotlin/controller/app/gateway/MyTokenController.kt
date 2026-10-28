package controller.app.gateway

import logic.GatewayErrorCodes
import logic.rejectField
import kotlinx.serialization.Serializable
import logic.TokenLogic
import model.GatewayToken
import neton.core.annotations.*
import neton.core.http.HttpException
import neton.core.http.NetonErrorCode
import neton.core.interfaces.Identity

@Serializable
data class CreateMyTokenRequest(
    val name: String,
    /** μUSD 预算；null = 不限（仍受账户余额约束） */
    val quotaBudget: Long? = null,
    val expiresAt: Long? = null,
    val allowedModels: String = "",
    val allowedIps: String = "",
    /** 计费分组（决定线路与倍率）；必须在 /groups 返回的范围里，空 = default。 */
    val group: String? = null,
)

/** 与 [CreateMyTokenRequest] 同一组字段 + id；刻意没有 status / 限流上限（运营设定）。 */
@Serializable
data class UpdateMyTokenRequest(
    val id: Long,
    val name: String,
    val quotaBudget: Long? = null,
    val expiresAt: Long? = null,
    val allowedModels: String = "",
    val allowedIps: String = "",
    /** 不带 = 分组不动；空 / default = 不绑。 */
    val group: String? = null,
)

/** 用户建 Key 时可选的分组。 */
@Serializable
data class MyGroupOptionVO(val code: String, val name: String, val description: String? = null, val ratio: String)

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
    /** 计费分组 code（没绑 = default）。 */
    val group: String = logic.BillingGroupResolver.DEFAULT_CODE,
    /** 分组是运营指定的：用户改不了。 */
    val groupAssigned: Boolean = false,
) {
    companion object {
        fun from(t: GatewayToken) = MyTokenVO(
            id = t.id, name = t.name, display = t.keyDisplay,
            quotaBudget = t.quotaBudget, quotaUsed = t.quotaUsed, expiresAt = t.expiresAt,
            allowedModels = t.allowedModels, allowedIps = t.allowedIps,
            rpmLimit = t.rpmLimit, tpmLimit = t.tpmLimit, concurrencyLimit = t.concurrencyLimit,
            status = t.status, createdAt = t.createdAt,
            group = t.groupOverride?.takeIf { it.isNotBlank() } ?: logic.BillingGroupResolver.DEFAULT_CODE,
            groupAssigned = t.groupAssigned == 1,
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

    /** 建 / 改 Key 时可选的分组：default + 「用户可选」的 + 运营单独开给他的。 */
    @Get("/groups")
    suspend fun groups(identity: Identity): List<MyGroupOptionVO> =
        logic.BillingGroupResolver.options(identity.id.toLong()).map { MyGroupOptionVO(it.code, it.name, it.description, it.ratio) }

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
            group = request.group,
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
            group = request.group,
        )
    }

    private fun validate(name: String, quotaBudget: Long?, expiresAt: Long?) {
        if (name.isBlank()) rejectField("name", GatewayErrorCodes.TOKEN_NAME_REQUIRED)
        quotaBudget?.let { if (it <= 0) rejectField("quotaBudget", GatewayErrorCodes.TOKEN_QUOTA_BUDGET_INVALID) }
        expiresAt?.let { if (it <= TokenLogic.nowMillis()) rejectField("expiresAt", GatewayErrorCodes.TOKEN_EXPIRES_AT_INVALID) }
    }

    @Put("/update-status")
    suspend fun updateStatus(identity: Identity, @Query id: Long, @Query status: Int) {
        requireOwned(identity, id)
        if (status != 0 && status != 1) rejectField("status", GatewayErrorCodes.TOKEN_STATUS_INVALID)
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
        if (t == null || t.userId != identity.id.toLong()) {
            throw HttpException(NetonErrorCode.RESOURCE_NOT_FOUND, GatewayErrorCodes.TOKEN_NOT_FOUND)
        }
    }
}
