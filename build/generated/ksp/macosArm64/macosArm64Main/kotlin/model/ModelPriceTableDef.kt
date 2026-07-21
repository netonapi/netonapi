// AUTO-GENERATED - DO NOT EDIT
package model

import kotlin.reflect.KProperty1
import neton.database.dsl.Column
import neton.database.dsl.ColumnType
import neton.database.dsl.TableDef

internal object ModelPriceTableDef : TableDef<ModelPrice> {
    override val tableName = "gateway_model_prices"

    val id = Column<ModelPrice, Long>(this, "id", "id", ColumnType.LONG, nullable = false)
    val model = Column<ModelPrice, String>(this, "model", "model", ColumnType.STRING, nullable = false)
    val inputPrice = Column<ModelPrice, String>(this, "input_price", "inputPrice", ColumnType.STRING, nullable = false)
    val outputPrice = Column<ModelPrice, String>(this, "output_price", "outputPrice", ColumnType.STRING, nullable = false)
    val cacheReadPrice = Column<ModelPrice, String>(this, "cache_read_price", "cacheReadPrice", ColumnType.STRING, nullable = false)
    val cacheWritePrice = Column<ModelPrice, String>(this, "cache_write_price", "cacheWritePrice", ColumnType.STRING, nullable = false)
    val perRequestPrice = Column<ModelPrice, Long?>(this, "per_request_price", "perRequestPrice", ColumnType.LONG, nullable = true)
    val saleOverride = Column<ModelPrice, String?>(this, "sale_override", "saleOverride", ColumnType.STRING, nullable = true)
    val source = Column<ModelPrice, String>(this, "source", "source", ColumnType.STRING, nullable = false)
    val deleted = Column<ModelPrice, Int>(this, "deleted", "deleted", ColumnType.INT, nullable = false)
    val createdAt = Column<ModelPrice, Long?>(this, "created_at", "createdAt", ColumnType.LONG, nullable = true)
    val updatedAt = Column<ModelPrice, Long?>(this, "updated_at", "updatedAt", ColumnType.LONG, nullable = true)

    override val columns = listOf(id, model, inputPrice, outputPrice, cacheReadPrice, cacheWritePrice, perRequestPrice, saleOverride, source, deleted, createdAt, updatedAt)

    // KSP 生成的精确映射，不依赖 runtime regex
    private val propMap: Map<String, Column<ModelPrice, *>> = mapOf(
        "id" to id,
        "model" to model,
        "inputPrice" to inputPrice,
        "outputPrice" to outputPrice,
        "cacheReadPrice" to cacheReadPrice,
        "cacheWritePrice" to cacheWritePrice,
        "perRequestPrice" to perRequestPrice,
        "saleOverride" to saleOverride,
        "source" to source,
        "deleted" to deleted,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )

    @Suppress("UNCHECKED_CAST")
    override fun <V> resolve(prop: KProperty1<ModelPrice, V>): Column<ModelPrice, V> =
        (propMap[prop.name] as? Column<ModelPrice, V>)
            ?: throw IllegalArgumentException("Unknown property: ${prop.name}")
}

// TableRef 扩展属性（JOIN 场景简写：U.id 代替 U[Entity::id]）
val neton.database.dsl.TableRef<ModelPrice>.id: neton.database.dsl.ColRef<ModelPrice, Long>
    get() = this[ModelPrice::id]
val neton.database.dsl.TableRef<ModelPrice>.model: neton.database.dsl.ColRef<ModelPrice, String>
    get() = this[ModelPrice::model]
val neton.database.dsl.TableRef<ModelPrice>.inputPrice: neton.database.dsl.ColRef<ModelPrice, String>
    get() = this[ModelPrice::inputPrice]
val neton.database.dsl.TableRef<ModelPrice>.outputPrice: neton.database.dsl.ColRef<ModelPrice, String>
    get() = this[ModelPrice::outputPrice]
val neton.database.dsl.TableRef<ModelPrice>.cacheReadPrice: neton.database.dsl.ColRef<ModelPrice, String>
    get() = this[ModelPrice::cacheReadPrice]
val neton.database.dsl.TableRef<ModelPrice>.cacheWritePrice: neton.database.dsl.ColRef<ModelPrice, String>
    get() = this[ModelPrice::cacheWritePrice]
val neton.database.dsl.TableRef<ModelPrice>.perRequestPrice: neton.database.dsl.ColRef<ModelPrice, Long?>
    get() = this[ModelPrice::perRequestPrice]
val neton.database.dsl.TableRef<ModelPrice>.saleOverride: neton.database.dsl.ColRef<ModelPrice, String?>
    get() = this[ModelPrice::saleOverride]
val neton.database.dsl.TableRef<ModelPrice>.source: neton.database.dsl.ColRef<ModelPrice, String>
    get() = this[ModelPrice::source]
val neton.database.dsl.TableRef<ModelPrice>.deleted: neton.database.dsl.ColRef<ModelPrice, Int>
    get() = this[ModelPrice::deleted]
val neton.database.dsl.TableRef<ModelPrice>.createdAt: neton.database.dsl.ColRef<ModelPrice, Long?>
    get() = this[ModelPrice::createdAt]
val neton.database.dsl.TableRef<ModelPrice>.updatedAt: neton.database.dsl.ColRef<ModelPrice, Long?>
    get() = this[ModelPrice::updatedAt]