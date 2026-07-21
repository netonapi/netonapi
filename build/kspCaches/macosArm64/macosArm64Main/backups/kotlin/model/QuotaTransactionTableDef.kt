// AUTO-GENERATED - DO NOT EDIT
package model

import kotlin.reflect.KProperty1
import neton.database.dsl.Column
import neton.database.dsl.ColumnType
import neton.database.dsl.TableDef

internal object QuotaTransactionTableDef : TableDef<QuotaTransaction> {
    override val tableName = "gateway_quota_transactions"

    val id = Column<QuotaTransaction, Long>(this, "id", "id", ColumnType.LONG, nullable = false)
    val userId = Column<QuotaTransaction, Long>(this, "user_id", "userId", ColumnType.LONG, nullable = false)
    val type = Column<QuotaTransaction, String>(this, "type", "type", ColumnType.STRING, nullable = false)
    val amount = Column<QuotaTransaction, Long>(this, "amount", "amount", ColumnType.LONG, nullable = false)
    val balanceAfter = Column<QuotaTransaction, Long>(this, "balance_after", "balanceAfter", ColumnType.LONG, nullable = false)
    val ref = Column<QuotaTransaction, String?>(this, "ref", "ref", ColumnType.STRING, nullable = true)
    val createdAt = Column<QuotaTransaction, Long?>(this, "created_at", "createdAt", ColumnType.LONG, nullable = true)

    override val columns = listOf(id, userId, type, amount, balanceAfter, ref, createdAt)

    // KSP 生成的精确映射，不依赖 runtime regex
    private val propMap: Map<String, Column<QuotaTransaction, *>> = mapOf(
        "id" to id,
        "userId" to userId,
        "type" to type,
        "amount" to amount,
        "balanceAfter" to balanceAfter,
        "ref" to ref,
        "createdAt" to createdAt
    )

    @Suppress("UNCHECKED_CAST")
    override fun <V> resolve(prop: KProperty1<QuotaTransaction, V>): Column<QuotaTransaction, V> =
        (propMap[prop.name] as? Column<QuotaTransaction, V>)
            ?: throw IllegalArgumentException("Unknown property: ${prop.name}")
}

// TableRef 扩展属性（JOIN 场景简写：U.id 代替 U[Entity::id]）
val neton.database.dsl.TableRef<QuotaTransaction>.id: neton.database.dsl.ColRef<QuotaTransaction, Long>
    get() = this[QuotaTransaction::id]
val neton.database.dsl.TableRef<QuotaTransaction>.userId: neton.database.dsl.ColRef<QuotaTransaction, Long>
    get() = this[QuotaTransaction::userId]
val neton.database.dsl.TableRef<QuotaTransaction>.type: neton.database.dsl.ColRef<QuotaTransaction, String>
    get() = this[QuotaTransaction::type]
val neton.database.dsl.TableRef<QuotaTransaction>.amount: neton.database.dsl.ColRef<QuotaTransaction, Long>
    get() = this[QuotaTransaction::amount]
val neton.database.dsl.TableRef<QuotaTransaction>.balanceAfter: neton.database.dsl.ColRef<QuotaTransaction, Long>
    get() = this[QuotaTransaction::balanceAfter]
val neton.database.dsl.TableRef<QuotaTransaction>.ref: neton.database.dsl.ColRef<QuotaTransaction, String?>
    get() = this[QuotaTransaction::ref]
val neton.database.dsl.TableRef<QuotaTransaction>.createdAt: neton.database.dsl.ColRef<QuotaTransaction, Long?>
    get() = this[QuotaTransaction::createdAt]