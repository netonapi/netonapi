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
        groupOverride: String? = null,
        rpmLimit: Int? = null,
        tpmLimit: Int? = null,
        concurrencyLimit: Int? = null,
    ): IssuedToken {
        val plaintext = generateKey()
        val record = GatewayTokenTable.insert(
            GatewayToken(
                userId = userId, name = name,
                keyHash = hashKey(plaintext), keyDisplay = display(plaintext),
                quotaBudget = quotaBudget, expiresAt = expiresAt,
                allowedModels = allowedModels, allowedIps = allowedIps,
                groupOverride = groupOverride,
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

    suspend fun updateStatus(id: Long, status: Int) {
        val current = GatewayTokenTable.get(id) ?: return
        GatewayTokenTable.update(current.copy(status = status))
    }

    suspend fun delete(id: Long) { GatewayTokenTable.destroy(id) }

    /**
     * 用户自助可改的字段。groupOverride / status / userId / 三个限流上限不在其列：计价组是运营给的
     * （S29 守着这条），限流上限由运营设定、用户不能自行放宽，状态走独立接口，归属永远不变。
     */
    suspend fun updateSelf(id: Long, name: String, quotaBudget: Long?, expiresAt: Long?, allowedModels: String, allowedIps: String) {
        val current = GatewayTokenTable.get(id) ?: return
        GatewayTokenTable.update(current.copy(
            name = name, quotaBudget = quotaBudget, expiresAt = expiresAt,
            allowedModels = allowedModels, allowedIps = allowedIps,
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
