// AUTO-GENERATED - DO NOT EDIT
package model

import kotlin.reflect.KProperty1
import neton.database.dsl.Column
import neton.database.dsl.ColumnType
import neton.database.dsl.TableDef

internal object GatewayGroupTableDef : TableDef<GatewayGroup> {
    override val tableName = "gateway_groups"

    val id = Column<GatewayGroup, Long>(this, "id", "id", ColumnType.LONG, nullable = false)
    val name = Column<GatewayGroup, String>(this, "name", "name", ColumnType.STRING, nullable = false)
    val ratio = Column<GatewayGroup, String>(this, "ratio", "ratio", ColumnType.STRING, nullable = false)
    val description = Column<GatewayGroup, String?>(this, "description", "description", ColumnType.STRING, nullable = true)
    val deleted = Column<GatewayGroup, Int>(this, "deleted", "deleted", ColumnType.INT, nullable = false)
    val createdAt = Column<GatewayGroup, Long?>(this, "created_at", "createdAt", ColumnType.LONG, nullable = true)
    val updatedAt = Column<GatewayGroup, Long?>(this, "updated_at", "updatedAt", ColumnType.LONG, nullable = true)

    override val columns = listOf(id, name, ratio, description, deleted, createdAt, updatedAt)

    // KSP 生成的精确映射，不依赖 runtime regex
    private val propMap: Map<String, Column<GatewayGroup, *>> = mapOf(
        "id" to id,
        "name" to name,
        "ratio" to ratio,
        "description" to description,
        "deleted" to deleted,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )

    @Suppress("UNCHECKED_CAST")
    override fun <V> resolve(prop: KProperty1<GatewayGroup, V>): Column<GatewayGroup, V> =
        (propMap[prop.name] as? Column<GatewayGroup, V>)
            ?: throw IllegalArgumentException("Unknown property: ${prop.name}")
}

// TableRef 扩展属性（JOIN 场景简写：U.id 代替 U[Entity::id]）
val neton.database.dsl.TableRef<GatewayGroup>.id: neton.database.dsl.ColRef<GatewayGroup, Long>
    get() = this[GatewayGroup::id]
val neton.database.dsl.TableRef<GatewayGroup>.name: neton.database.dsl.ColRef<GatewayGroup, String>
    get() = this[GatewayGroup::name]
val neton.database.dsl.TableRef<GatewayGroup>.ratio: neton.database.dsl.ColRef<GatewayGroup, String>
    get() = this[GatewayGroup::ratio]
val neton.database.dsl.TableRef<GatewayGroup>.description: neton.database.dsl.ColRef<GatewayGroup, String?>
    get() = this[GatewayGroup::description]
val neton.database.dsl.TableRef<GatewayGroup>.deleted: neton.database.dsl.ColRef<GatewayGroup, Int>
    get() = this[GatewayGroup::deleted]
val neton.database.dsl.TableRef<GatewayGroup>.createdAt: neton.database.dsl.ColRef<GatewayGroup, Long?>
    get() = this[GatewayGroup::createdAt]
val neton.database.dsl.TableRef<GatewayGroup>.updatedAt: neton.database.dsl.ColRef<GatewayGroup, Long?>
    get() = this[GatewayGroup::updatedAt]