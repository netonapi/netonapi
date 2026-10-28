package logic

import model.GatewayToken
import neton.database.dsl.*
import neton.logging.Logger
import neton.security.digest.Sha256
import neton.security.random.SecureRandom
import table.GatewayTokenTable

data class IssuedToken(val plaintext: String, val record: GatewayToken)

@neton.core.annotations.Logic(logger = "logic.gateway-token")
class TokenLogic(private val log: Logger) {

    suspend fun issue(
        userId: Long,
        name: String,
        quotaBudget: Long? = null,
        expiresAt: Long? = null,
        allowedModels: String = "",
        allowedIps: String = "",
        /** 用户自己选的分组；必须在 [BillingGroupResolver.options] 里，空 / default = 不绑。 */
        group: String? = null,
        rpmLimit: Int? = null,
        tpmLimit: Int? = null,
        concurrencyLimit: Int? = null,
    ): IssuedToken {
        val chosen = selectableGroupOrNull(userId, group)
        val plaintext = generateKey()
        val record = GatewayTokenTable.insert(
            GatewayToken(
                userId = userId, name = name,
                keyHash = hashKey(plaintext), keyDisplay = display(plaintext),
                quotaBudget = quotaBudget, expiresAt = expiresAt,
                allowedModels = allowedModels, allowedIps = allowedIps,
                groupOverride = chosen,
                rpmLimit = rpmLimit, tpmLimit = tpmLimit, concurrencyLimit = concurrencyLimit,
            )
        )
        log.info("gateway token issued id=${record.id} user=$userId display=${record.keyDisplay}")
        return IssuedToken(plaintext, record)
    }

    /** TokenGuard 入口：明文 → 哈希查表。过期/禁用返回 null。 */
    suspend fun verify(plaintext: String): GatewayToken? {
        val token = GatewayTokenTable.oneWhere { GatewayToken::keyHash eq hashKey(plaintext) } ?: return null
        if (token.status != 1) return null
        token.expiresAt?.let { if (it < nowMillis()) return null }
        return token
    }

    suspend fun get(id: Long): GatewayToken? = GatewayTokenTable.get(id)

    suspend fun listByUser(userId: Long): List<GatewayToken> =
        GatewayTokenTable.query { where { GatewayToken::userId eq userId } }.list()

    /**
     * 运营给某个令牌指定分组（特价 / 专线，用户改不了，见 BillingGroupResolver）。空 = 取消，Key 回到 default、用户可重新选。
     * 组必须存在：指向不存在的组会让这个令牌每次请求都 500（CONFIG_BROKEN），不如在写入时就拒绝。
     */
    suspend fun updateGroup(id: Long, groupCode: String?) {
        val current = GatewayTokenTable.get(id) ?: return
        val code = liveGroupOrNull(groupCode)
        GatewayTokenTable.update(current.copy(groupOverride = code, groupAssigned = if (code != null) 1 else 0))
    }

    /** 用户自选分组：空或 default → null（不绑）；其余必须此刻对这个用户开放。 */
    private suspend fun selectableGroupOrNull(userId: Long, groupCode: String?): String? {
        val code = groupCode?.trim()?.takeIf { it.isNotEmpty() && it != BillingGroupResolver.DEFAULT_CODE } ?: return null
        if (BillingGroupResolver.options(userId).none { it.code == code }) {
            rejectField("group", GatewayErrorCodes.TOKEN_GROUP_NOT_ALLOWED)
        }
        return code
    }

    /** 空 → null；非空必须是存在的计费组，否则拒绝（悬空组让这个令牌每次请求都 500）。 */
    private suspend fun liveGroupOrNull(groupCode: String?): String? {
        val code = groupCode?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (table.GatewayGroupTable.oneWhere { model.GatewayGroup::code eq code } == null) {
            rejectField("groupCode", GatewayErrorCodes.GROUP_CODE_UNKNOWN)
        }
        return code
    }

    suspend fun updateLimits(id: Long, rpmLimit: Int?, tpmLimit: Int?, concurrencyLimit: Int?) {
        val current = GatewayTokenTable.get(id)
            ?: throw neton.core.http.HttpException(neton.core.http.NetonErrorCode.RESOURCE_NOT_FOUND, GatewayErrorCodes.TOKEN_NOT_FOUND)
        GatewayTokenTable.update(current.copy(rpmLimit = rpmLimit, tpmLimit = tpmLimit, concurrencyLimit = concurrencyLimit))
    }

    suspend fun updateStatus(id: Long, status: Int) {
        val current = GatewayTokenTable.get(id) ?: return
        GatewayTokenTable.update(current.copy(status = status))
    }

    suspend fun delete(id: Long) { GatewayTokenTable.destroy(id) }

    /**
     * 用户自助可改的字段。分组只能在对他开放的范围里换；运营指定的分组（groupAssigned）改不了。
     * status / userId / 三个限流上限不在其列：限流上限由运营设定、用户不能自行放宽，状态走独立接口，归属永远不变。
     */
    suspend fun updateSelf(id: Long, name: String, quotaBudget: Long?, expiresAt: Long?, allowedModels: String, allowedIps: String, group: String? = null) {
        val current = GatewayTokenTable.get(id) ?: return
        // 不带 group（老客户端 / 只改名称）= 分组不动；带了才按「换分组」处理。空与 default 都表示「不绑」。
        fun norm(code: String?) = code?.trim()?.takeIf { it.isNotEmpty() && it != BillingGroupResolver.DEFAULT_CODE }
        val wanted = norm(group)
        val nextGroup = when {
            group == null || wanted == norm(current.groupOverride) -> current.groupOverride
            current.groupAssigned == 1 -> rejectField("group", GatewayErrorCodes.TOKEN_GROUP_LOCKED)
            else -> selectableGroupOrNull(current.userId, wanted)
        }
        GatewayTokenTable.update(current.copy(
            name = name, quotaBudget = quotaBudget, expiresAt = expiresAt,
            allowedModels = allowedModels, allowedIps = allowedIps, groupOverride = nextGroup,
        ))
    }

    companion object {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        // API 令牌必须用 CSPRNG：kotlin.random.Random 可由若干输出推断内部状态并预测后续令牌。
        // SecureRandom.string 内部用拒绝采样，消除 62 字符表在 256 上的取模偏置（否则熵降低）。
        fun generateKey(): String = "sk-" + SecureRandom.string(48, ALPHABET)

        fun hashKey(plaintext: String): String = Sha256.hex(plaintext)

        fun display(key: String): String =
            if (key.length <= 15) "****" else key.take(7) + "****" + key.takeLast(4)

        fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
    }
}
