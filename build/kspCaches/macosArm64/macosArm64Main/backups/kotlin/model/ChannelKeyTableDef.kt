// AUTO-GENERATED - DO NOT EDIT
package model

import kotlin.reflect.KProperty1
import neton.database.dsl.Column
import neton.database.dsl.ColumnType
import neton.database.dsl.TableDef

internal object ChannelKeyTableDef : TableDef<ChannelKey> {
    override val tableName = "gateway_channel_keys"

    val id = Column<ChannelKey, Long>(this, "id", "id", ColumnType.LONG, nullable = false)
    val channelId = Column<ChannelKey, Long>(this, "channel_id", "channelId", ColumnType.LONG, nullable = false)
    val apiKey = Column<ChannelKey, String>(this, "api_key", "apiKey", ColumnType.STRING, nullable = false)
    val status = Column<ChannelKey, Int>(this, "status", "status", ColumnType.INT, nullable = false)
    val failCount = Column<ChannelKey, Int>(this, "fail_count", "failCount", ColumnType.INT, nullable = false)
    val lastError = Column<ChannelKey, String?>(this, "last_error", "lastError", ColumnType.STRING, nullable = true)
    val lastUsedAt = Column<ChannelKey, Long?>(this, "last_used_at", "lastUsedAt", ColumnType.LONG, nullable = true)
    val deleted = Column<ChannelKey, Int>(this, "deleted", "deleted", ColumnType.INT, nullable = false)
    val createdAt = Column<ChannelKey, Long?>(this, "created_at", "createdAt", ColumnType.LONG, nullable = true)
    val updatedAt = Column<ChannelKey, Long?>(this, "updated_at", "updatedAt", ColumnType.LONG, nullable = true)

    override val columns = listOf(id, channelId, apiKey, status, failCount, lastError, lastUsedAt, deleted, createdAt, updatedAt)

    // KSP 生成的精确映射，不依赖 runtime regex
    private val propMap: Map<String, Column<ChannelKey, *>> = mapOf(
        "id" to id,
        "channelId" to channelId,
        "apiKey" to apiKey,
        "status" to status,
        "failCount" to failCount,
        "lastError" to lastError,
        "lastUsedAt" to lastUsedAt,
        "deleted" to deleted,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )

    @Suppress("UNCHECKED_CAST")
    override fun <V> resolve(prop: KProperty1<ChannelKey, V>): Column<ChannelKey, V> =
        (propMap[prop.name] as? Column<ChannelKey, V>)
            ?: throw IllegalArgumentException("Unknown property: ${prop.name}")
}

// TableRef 扩展属性（JOIN 场景简写：U.id 代替 U[Entity::id]）
val neton.database.dsl.TableRef<ChannelKey>.id: neton.database.dsl.ColRef<ChannelKey, Long>
    get() = this[ChannelKey::id]
val neton.database.dsl.TableRef<ChannelKey>.channelId: neton.database.dsl.ColRef<ChannelKey, Long>
    get() = this[ChannelKey::channelId]
val neton.database.dsl.TableRef<ChannelKey>.apiKey: neton.database.dsl.ColRef<ChannelKey, String>
    get() = this[ChannelKey::apiKey]
val neton.database.dsl.TableRef<ChannelKey>.status: neton.database.dsl.ColRef<ChannelKey, Int>
    get() = this[ChannelKey::status]
val neton.database.dsl.TableRef<ChannelKey>.failCount: neton.database.dsl.ColRef<ChannelKey, Int>
    get() = this[ChannelKey::failCount]
val neton.database.dsl.TableRef<ChannelKey>.lastError: neton.database.dsl.ColRef<ChannelKey, String?>
    get() = this[ChannelKey::lastError]
val neton.database.dsl.TableRef<ChannelKey>.lastUsedAt: neton.database.dsl.ColRef<ChannelKey, Long?>
    get() = this[ChannelKey::lastUsedAt]
val neton.database.dsl.TableRef<ChannelKey>.deleted: neton.database.dsl.ColRef<ChannelKey, Int>
    get() = this[ChannelKey::deleted]
val neton.database.dsl.TableRef<ChannelKey>.createdAt: neton.database.dsl.ColRef<ChannelKey, Long?>
    get() = this[ChannelKey::createdAt]
val neton.database.dsl.TableRef<ChannelKey>.updatedAt: neton.database.dsl.ColRef<ChannelKey, Long?>
    get() = this[ChannelKey::updatedAt]