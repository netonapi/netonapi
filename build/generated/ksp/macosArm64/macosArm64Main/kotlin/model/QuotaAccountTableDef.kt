// AUTO-GENERATED - DO NOT EDIT
package model

import kotlin.reflect.KProperty1
import neton.database.dsl.Column
import neton.database.dsl.ColumnType
import neton.database.dsl.TableDef

internal object QuotaAccountTableDef : TableDef<QuotaAccount> {
    override val tableName = "gateway_quota_accounts"

    val id = Column<QuotaAccount, Long>(this, "id", "id", ColumnType.LONG, nullable = false)
    val userId = Column<QuotaAccount, Long>(this, "user_id", "userId", ColumnType.LONG, nullable = false)
    val balance = Column<QuotaAccount, Long>(this, "balance", "balance", ColumnType.LONG, nullable = false)
    val version = Column<QuotaAccount, Long>(this, "version", "version", ColumnType.LONG, nullable = false)
    val createdAt = Column<QuotaAccount, Long?>(this, "created_at", "createdAt", ColumnType.LONG, nullable = true)
    val updatedAt = Column<QuotaAccount, Long?>(this, "updated_at", "updatedAt", ColumnType.LONG, nullable = true)

    override val columns = listOf(id, userId, balance, version, createdAt, updatedAt)

    // KSP 生成的精确映射，不依赖 runtime regex
    private val propMap: Map<String, Column<QuotaAccount, *>> = mapOf(
        "id" to id,
        "userId" to userId,
        "balance" to balance,
        "version" to version,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )

    @Suppress("UNCHECKED_CAST")
    override fun <V> resolve(prop: KProperty1<QuotaAccount, V>): Column<QuotaAccount, V> =
        (propMap[prop.name] as? Column<QuotaAccount, V>)
            ?: throw IllegalArgumentException("Unknown property: ${prop.name}")
}

// TableRef 扩展属性（JOIN 场景简写：U.id 代替 U[Entity::id]）
val neton.database.dsl.TableRef<QuotaAccount>.id: neton.database.dsl.ColRef<QuotaAccount, Long>
    get() = this[QuotaAccount::id]
val neton.database.dsl.TableRef<QuotaAccount>.userId: neton.database.dsl.ColRef<QuotaAccount, Long>
    get() = this[QuotaAccount::userId]
val neton.database.dsl.TableRef<QuotaAccount>.balance: neton.database.dsl.ColRef<QuotaAccount, Long>
    get() = this[QuotaAccount::balance]
val neton.database.dsl.TableRef<QuotaAccount>.version: neton.database.dsl.ColRef<QuotaAccount, Long>
    get() = this[QuotaAccount::version]
val neton.database.dsl.TableRef<QuotaAccount>.createdAt: neton.database.dsl.ColRef<QuotaAccount, Long?>
    get() = this[QuotaAccount::createdAt]
val neton.database.dsl.TableRef<QuotaAccount>.updatedAt: neton.database.dsl.ColRef<QuotaAccount, Long?>
    get() = this[QuotaAccount::updatedAt]