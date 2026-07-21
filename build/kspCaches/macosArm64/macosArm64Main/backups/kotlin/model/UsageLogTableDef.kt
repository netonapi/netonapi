// AUTO-GENERATED - DO NOT EDIT
package model

import kotlin.reflect.KProperty1
import neton.database.dsl.Column
import neton.database.dsl.ColumnType
import neton.database.dsl.TableDef

internal object UsageLogTableDef : TableDef<UsageLog> {
    override val tableName = "gateway_usage_logs"

    val id = Column<UsageLog, Long>(this, "id", "id", ColumnType.LONG, nullable = false)
    val userId = Column<UsageLog, Long>(this, "user_id", "userId", ColumnType.LONG, nullable = false)
    val tokenId = Column<UsageLog, Long>(this, "token_id", "tokenId", ColumnType.LONG, nullable = false)
    val channelId = Column<UsageLog, Long>(this, "channel_id", "channelId", ColumnType.LONG, nullable = false)
    val requestModel = Column<UsageLog, String>(this, "request_model", "requestModel", ColumnType.STRING, nullable = false)
    val upstreamModel = Column<UsageLog, String>(this, "upstream_model", "upstreamModel", ColumnType.STRING, nullable = false)
    val promptTokens = Column<UsageLog, Int>(this, "prompt_tokens", "promptTokens", ColumnType.INT, nullable = false)
    val completionTokens = Column<UsageLog, Int>(this, "completion_tokens", "completionTokens", ColumnType.INT, nullable = false)
    val cacheReadTokens = Column<UsageLog, Int>(this, "cache_read_tokens", "cacheReadTokens", ColumnType.INT, nullable = false)
    val cacheWriteTokens = Column<UsageLog, Int>(this, "cache_write_tokens", "cacheWriteTokens", ColumnType.INT, nullable = false)
    val charged = Column<UsageLog, Long>(this, "charged", "charged", ColumnType.LONG, nullable = false)
    val cost = Column<UsageLog, Long>(this, "cost", "cost", ColumnType.LONG, nullable = false)
    val ttfbMs = Column<UsageLog, Long?>(this, "ttfb_ms", "ttfbMs", ColumnType.LONG, nullable = true)
    val latencyMs = Column<UsageLog, Long?>(this, "latency_ms", "latencyMs", ColumnType.LONG, nullable = true)
    val stream = Column<UsageLog, Int>(this, "stream", "stream", ColumnType.INT, nullable = false)
    val status = Column<UsageLog, String>(this, "status", "status", ColumnType.STRING, nullable = false)
    val errorCode = Column<UsageLog, String?>(this, "error_code", "errorCode", ColumnType.STRING, nullable = true)
    val createdAt = Column<UsageLog, Long?>(this, "created_at", "createdAt", ColumnType.LONG, nullable = true)

    override val columns = listOf(id, userId, tokenId, channelId, requestModel, upstreamModel, promptTokens, completionTokens, cacheReadTokens, cacheWriteTokens, charged, cost, ttfbMs, latencyMs, stream, status, errorCode, createdAt)

    // KSP 生成的精确映射，不依赖 runtime regex
    private val propMap: Map<String, Column<UsageLog, *>> = mapOf(
        "id" to id,
        "userId" to userId,
        "tokenId" to tokenId,
        "channelId" to channelId,
        "requestModel" to requestModel,
        "upstreamModel" to upstreamModel,
        "promptTokens" to promptTokens,
        "completionTokens" to completionTokens,
        "cacheReadTokens" to cacheReadTokens,
        "cacheWriteTokens" to cacheWriteTokens,
        "charged" to charged,
        "cost" to cost,
        "ttfbMs" to ttfbMs,
        "latencyMs" to latencyMs,
        "stream" to stream,
        "status" to status,
        "errorCode" to errorCode,
        "createdAt" to createdAt
    )

    @Suppress("UNCHECKED_CAST")
    override fun <V> resolve(prop: KProperty1<UsageLog, V>): Column<UsageLog, V> =
        (propMap[prop.name] as? Column<UsageLog, V>)
            ?: throw IllegalArgumentException("Unknown property: ${prop.name}")
}

// TableRef 扩展属性（JOIN 场景简写：U.id 代替 U[Entity::id]）
val neton.database.dsl.TableRef<UsageLog>.id: neton.database.dsl.ColRef<UsageLog, Long>
    get() = this[UsageLog::id]
val neton.database.dsl.TableRef<UsageLog>.userId: neton.database.dsl.ColRef<UsageLog, Long>
    get() = this[UsageLog::userId]
val neton.database.dsl.TableRef<UsageLog>.tokenId: neton.database.dsl.ColRef<UsageLog, Long>
    get() = this[UsageLog::tokenId]
val neton.database.dsl.TableRef<UsageLog>.channelId: neton.database.dsl.ColRef<UsageLog, Long>
    get() = this[UsageLog::channelId]
val neton.database.dsl.TableRef<UsageLog>.requestModel: neton.database.dsl.ColRef<UsageLog, String>
    get() = this[UsageLog::requestModel]
val neton.database.dsl.TableRef<UsageLog>.upstreamModel: neton.database.dsl.ColRef<UsageLog, String>
    get() = this[UsageLog::upstreamModel]
val neton.database.dsl.TableRef<UsageLog>.promptTokens: neton.database.dsl.ColRef<UsageLog, Int>
    get() = this[UsageLog::promptTokens]
val neton.database.dsl.TableRef<UsageLog>.completionTokens: neton.database.dsl.ColRef<UsageLog, Int>
    get() = this[UsageLog::completionTokens]
val neton.database.dsl.TableRef<UsageLog>.cacheReadTokens: neton.database.dsl.ColRef<UsageLog, Int>
    get() = this[UsageLog::cacheReadTokens]
val neton.database.dsl.TableRef<UsageLog>.cacheWriteTokens: neton.database.dsl.ColRef<UsageLog, Int>
    get() = this[UsageLog::cacheWriteTokens]
val neton.database.dsl.TableRef<UsageLog>.charged: neton.database.dsl.ColRef<UsageLog, Long>
    get() = this[UsageLog::charged]
val neton.database.dsl.TableRef<UsageLog>.cost: neton.database.dsl.ColRef<UsageLog, Long>
    get() = this[UsageLog::cost]
val neton.database.dsl.TableRef<UsageLog>.ttfbMs: neton.database.dsl.ColRef<UsageLog, Long?>
    get() = this[UsageLog::ttfbMs]
val neton.database.dsl.TableRef<UsageLog>.latencyMs: neton.database.dsl.ColRef<UsageLog, Long?>
    get() = this[UsageLog::latencyMs]
val neton.database.dsl.TableRef<UsageLog>.stream: neton.database.dsl.ColRef<UsageLog, Int>
    get() = this[UsageLog::stream]
val neton.database.dsl.TableRef<UsageLog>.status: neton.database.dsl.ColRef<UsageLog, String>
    get() = this[UsageLog::status]
val neton.database.dsl.TableRef<UsageLog>.errorCode: neton.database.dsl.ColRef<UsageLog, String?>
    get() = this[UsageLog::errorCode]
val neton.database.dsl.TableRef<UsageLog>.createdAt: neton.database.dsl.ColRef<UsageLog, Long?>
    get() = this[UsageLog::createdAt]