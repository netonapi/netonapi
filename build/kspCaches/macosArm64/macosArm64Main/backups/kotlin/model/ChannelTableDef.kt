// AUTO-GENERATED - DO NOT EDIT
package model

import kotlin.reflect.KProperty1
import neton.database.dsl.Column
import neton.database.dsl.ColumnType
import neton.database.dsl.TableDef

internal object ChannelTableDef : TableDef<Channel> {
    override val tableName = "gateway_channels"

    val id = Column<Channel, Long>(this, "id", "id", ColumnType.LONG, nullable = false)
    val name = Column<Channel, String>(this, "name", "name", ColumnType.STRING, nullable = false)
    val type = Column<Channel, String>(this, "type", "type", ColumnType.STRING, nullable = false)
    val baseUrl = Column<Channel, String>(this, "base_url", "baseUrl", ColumnType.STRING, nullable = false)
    val groups = Column<Channel, String>(this, "groups", "groups", ColumnType.STRING, nullable = false)
    val models = Column<Channel, String>(this, "models", "models", ColumnType.STRING, nullable = false)
    val modelMapping = Column<Channel, String?>(this, "model_mapping", "modelMapping", ColumnType.STRING, nullable = true)
    val paramOverride = Column<Channel, String?>(this, "param_override", "paramOverride", ColumnType.STRING, nullable = true)
    val priority = Column<Channel, Int>(this, "priority", "priority", ColumnType.INT, nullable = false)
    val weight = Column<Channel, Int>(this, "weight", "weight", ColumnType.INT, nullable = false)
    val status = Column<Channel, Int>(this, "status", "status", ColumnType.INT, nullable = false)
    val ttfbTimeoutMs = Column<Channel, Long>(this, "ttfb_timeout_ms", "ttfbTimeoutMs", ColumnType.LONG, nullable = false)
    val idleTimeoutMs = Column<Channel, Long>(this, "idle_timeout_ms", "idleTimeoutMs", ColumnType.LONG, nullable = false)
    val proxyUrl = Column<Channel, String?>(this, "proxy_url", "proxyUrl", ColumnType.STRING, nullable = true)
    val costDiscount = Column<Channel, String>(this, "cost_discount", "costDiscount", ColumnType.STRING, nullable = false)
    val remark = Column<Channel, String?>(this, "remark", "remark", ColumnType.STRING, nullable = true)
    val deleted = Column<Channel, Int>(this, "deleted", "deleted", ColumnType.INT, nullable = false)
    val createdAt = Column<Channel, Long?>(this, "created_at", "createdAt", ColumnType.LONG, nullable = true)
    val updatedAt = Column<Channel, Long?>(this, "updated_at", "updatedAt", ColumnType.LONG, nullable = true)

    override val columns = listOf(id, name, type, baseUrl, groups, models, modelMapping, paramOverride, priority, weight, status, ttfbTimeoutMs, idleTimeoutMs, proxyUrl, costDiscount, remark, deleted, createdAt, updatedAt)

    // KSP 生成的精确映射，不依赖 runtime regex
    private val propMap: Map<String, Column<Channel, *>> = mapOf(
        "id" to id,
        "name" to name,
        "type" to type,
        "baseUrl" to baseUrl,
        "groups" to groups,
        "models" to models,
        "modelMapping" to modelMapping,
        "paramOverride" to paramOverride,
        "priority" to priority,
        "weight" to weight,
        "status" to status,
        "ttfbTimeoutMs" to ttfbTimeoutMs,
        "idleTimeoutMs" to idleTimeoutMs,
        "proxyUrl" to proxyUrl,
        "costDiscount" to costDiscount,
        "remark" to remark,
        "deleted" to deleted,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )

    @Suppress("UNCHECKED_CAST")
    override fun <V> resolve(prop: KProperty1<Channel, V>): Column<Channel, V> =
        (propMap[prop.name] as? Column<Channel, V>)
            ?: throw IllegalArgumentException("Unknown property: ${prop.name}")
}

// TableRef 扩展属性（JOIN 场景简写：U.id 代替 U[Entity::id]）
val neton.database.dsl.TableRef<Channel>.id: neton.database.dsl.ColRef<Channel, Long>
    get() = this[Channel::id]
val neton.database.dsl.TableRef<Channel>.name: neton.database.dsl.ColRef<Channel, String>
    get() = this[Channel::name]
val neton.database.dsl.TableRef<Channel>.type: neton.database.dsl.ColRef<Channel, String>
    get() = this[Channel::type]
val neton.database.dsl.TableRef<Channel>.baseUrl: neton.database.dsl.ColRef<Channel, String>
    get() = this[Channel::baseUrl]
val neton.database.dsl.TableRef<Channel>.groups: neton.database.dsl.ColRef<Channel, String>
    get() = this[Channel::groups]
val neton.database.dsl.TableRef<Channel>.models: neton.database.dsl.ColRef<Channel, String>
    get() = this[Channel::models]
val neton.database.dsl.TableRef<Channel>.modelMapping: neton.database.dsl.ColRef<Channel, String?>
    get() = this[Channel::modelMapping]
val neton.database.dsl.TableRef<Channel>.paramOverride: neton.database.dsl.ColRef<Channel, String?>
    get() = this[Channel::paramOverride]
val neton.database.dsl.TableRef<Channel>.priority: neton.database.dsl.ColRef<Channel, Int>
    get() = this[Channel::priority]
val neton.database.dsl.TableRef<Channel>.weight: neton.database.dsl.ColRef<Channel, Int>
    get() = this[Channel::weight]
val neton.database.dsl.TableRef<Channel>.status: neton.database.dsl.ColRef<Channel, Int>
    get() = this[Channel::status]
val neton.database.dsl.TableRef<Channel>.ttfbTimeoutMs: neton.database.dsl.ColRef<Channel, Long>
    get() = this[Channel::ttfbTimeoutMs]
val neton.database.dsl.TableRef<Channel>.idleTimeoutMs: neton.database.dsl.ColRef<Channel, Long>
    get() = this[Channel::idleTimeoutMs]
val neton.database.dsl.TableRef<Channel>.proxyUrl: neton.database.dsl.ColRef<Channel, String?>
    get() = this[Channel::proxyUrl]
val neton.database.dsl.TableRef<Channel>.costDiscount: neton.database.dsl.ColRef<Channel, String>
    get() = this[Channel::costDiscount]
val neton.database.dsl.TableRef<Channel>.remark: neton.database.dsl.ColRef<Channel, String?>
    get() = this[Channel::remark]
val neton.database.dsl.TableRef<Channel>.deleted: neton.database.dsl.ColRef<Channel, Int>
    get() = this[Channel::deleted]
val neton.database.dsl.TableRef<Channel>.createdAt: neton.database.dsl.ColRef<Channel, Long?>
    get() = this[Channel::createdAt]
val neton.database.dsl.TableRef<Channel>.updatedAt: neton.database.dsl.ColRef<Channel, Long?>
    get() = this[Channel::updatedAt]