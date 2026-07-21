package logic

import model.GatewayToken
import neton.database.dsl.*
import neton.logging.Logger
import neton.security.digest.Sha256
import table.GatewayTokenTable
import kotlin.random.Random

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
    ): IssuedToken {
        val plaintext = generateKey()
        val record = GatewayTokenTable.insert(
            GatewayToken(
                userId = userId, name = name,
                keyHash = hashKey(plaintext), keyDisplay = display(plaintext),
                quotaBudget = quotaBudget, expiresAt = expiresAt,
                allowedModels = allowedModels, allowedIps = allowedIps,
                groupOverride = groupOverride,
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

    companion object {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        fun generateKey(): String =
            "sk-" + buildString(48) { repeat(48) { append(ALPHABET[Random.nextInt(ALPHABET.length)]) } }

        fun hashKey(plaintext: String): String = Sha256.hex(plaintext)

        fun display(key: String): String =
            if (key.length <= 15) "****" else key.take(7) + "****" + key.takeLast(4)

        fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
    }
}
