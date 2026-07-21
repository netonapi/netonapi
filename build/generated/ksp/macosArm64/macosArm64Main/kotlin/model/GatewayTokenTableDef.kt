// AUTO-GENERATED - DO NOT EDIT
package model

import kotlin.reflect.KProperty1
import neton.database.dsl.Column
import neton.database.dsl.ColumnType
import neton.database.dsl.TableDef

internal object GatewayTokenTableDef : TableDef<GatewayToken> {
    override val tableName = "gateway_tokens"

    val id = Column<GatewayToken, Long>(this, "id", "id", ColumnType.LONG, nullable = false)
    val userId = Column<GatewayToken, Long>(this, "user_id", "userId", ColumnType.LONG, nullable = false)
    val name = Column<GatewayToken, String>(this, "name", "name", ColumnType.STRING, nullable = false)
    val keyHash = Column<GatewayToken, String>(this, "key_hash", "keyHash", ColumnType.STRING, nullable = false)
    val keyDisplay = Column<GatewayToken, String>(this, "key_display", "keyDisplay", ColumnType.STRING, nullable = false)
    val quotaBudget = Column<GatewayToken, Long?>(this, "quota_budget", "quotaBudget", ColumnType.LONG, nullable = true)
    val quotaUsed = Column<GatewayToken, Long>(this, "quota_used", "quotaUsed", ColumnType.LONG, nullable = false)
    val expiresAt = Column<GatewayToken, Long?>(this, "expires_at", "expiresAt", ColumnType.LONG, nullable = true)
    val allowedModels = Column<GatewayToken, String>(this, "allowed_models", "allowedModels", ColumnType.STRING, nullable = false)
    val allowedIps = Column<GatewayToken, String>(this, "allowed_ips", "allowedIps", ColumnType.STRING, nullable = false)
    val groupOverride = Column<GatewayToken, String?>(this, "group_override", "groupOverride", ColumnType.STRING, nullable = true)
    val status = Column<GatewayToken, Int>(this, "status", "status", ColumnType.INT, nullable = false)
    val deleted = Column<GatewayToken, Int>(this, "deleted", "deleted", ColumnType.INT, nullable = false)
    val createdAt = Column<GatewayToken, Long?>(this, "created_at", "createdAt", ColumnType.LONG, nullable = true)
    val updatedAt = Column<GatewayToken, Long?>(this, "updated_at", "updatedAt", ColumnType.LONG, nullable = true)

    override val columns = listOf(id, userId, name, keyHash, keyDisplay, quotaBudget, quotaUsed, expiresAt, allowedModels, allowedIps, groupOverride, status, deleted, createdAt, updatedAt)

    // KSP 生成的精确映射，不依赖 runtime regex
    private val propMap: Map<String, Column<GatewayToken, *>> = mapOf(
        "id" to id,
        "userId" to userId,
        "name" to name,
        "keyHash" to keyHash,
        "keyDisplay" to keyDisplay,
        "quotaBudget" to quotaBudget,
        "quotaUsed" to quotaUsed,
        "expiresAt" to expiresAt,
        "allowedModels" to allowedModels,
        "allowedIps" to allowedIps,
        "groupOverride" to groupOverride,
        "status" to status,
        "deleted" to deleted,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )

    @Suppress("UNCHECKED_CAST")
    override fun <V> resolve(prop: KProperty1<GatewayToken, V>): Column<GatewayToken, V> =
        (propMap[prop.name] as? Column<GatewayToken, V>)
            ?: throw IllegalArgumentException("Unknown property: ${prop.name}")
}

// TableRef 扩展属性（JOIN 场景简写：U.id 代替 U[Entity::id]）
val neton.database.dsl.TableRef<GatewayToken>.id: neton.database.dsl.ColRef<GatewayToken, Long>
    get() = this[GatewayToken::id]
val neton.database.dsl.TableRef<GatewayToken>.userId: neton.database.dsl.ColRef<GatewayToken, Long>
    get() = this[GatewayToken::userId]
val neton.database.dsl.TableRef<GatewayToken>.name: neton.database.dsl.ColRef<GatewayToken, String>
    get() = this[GatewayToken::name]
val neton.database.dsl.TableRef<GatewayToken>.keyHash: neton.database.dsl.ColRef<GatewayToken, String>
    get() = this[GatewayToken::keyHash]
val neton.database.dsl.TableRef<GatewayToken>.keyDisplay: neton.database.dsl.ColRef<GatewayToken, String>
    get() = this[GatewayToken::keyDisplay]
val neton.database.dsl.TableRef<GatewayToken>.quotaBudget: neton.database.dsl.ColRef<GatewayToken, Long?>
    get() = this[GatewayToken::quotaBudget]
val neton.database.dsl.TableRef<GatewayToken>.quotaUsed: neton.database.dsl.ColRef<GatewayToken, Long>
    get() = this[GatewayToken::quotaUsed]
val neton.database.dsl.TableRef<GatewayToken>.expiresAt: neton.database.dsl.ColRef<GatewayToken, Long?>
    get() = this[GatewayToken::expiresAt]
val neton.database.dsl.TableRef<GatewayToken>.allowedModels: neton.database.dsl.ColRef<GatewayToken, String>
    get() = this[GatewayToken::allowedModels]
val neton.database.dsl.TableRef<GatewayToken>.allowedIps: neton.database.dsl.ColRef<GatewayToken, String>
    get() = this[GatewayToken::allowedIps]
val neton.database.dsl.TableRef<GatewayToken>.groupOverride: neton.database.dsl.ColRef<GatewayToken, String?>
    get() = this[GatewayToken::groupOverride]
val neton.database.dsl.TableRef<GatewayToken>.status: neton.database.dsl.ColRef<GatewayToken, Int>
    get() = this[GatewayToken::status]
val neton.database.dsl.TableRef<GatewayToken>.deleted: neton.database.dsl.ColRef<GatewayToken, Int>
    get() = this[GatewayToken::deleted]
val neton.database.dsl.TableRef<GatewayToken>.createdAt: neton.database.dsl.ColRef<GatewayToken, Long?>
    get() = this[GatewayToken::createdAt]
val neton.database.dsl.TableRef<GatewayToken>.updatedAt: neton.database.dsl.ColRef<GatewayToken, Long?>
    get() = this[GatewayToken::updatedAt]