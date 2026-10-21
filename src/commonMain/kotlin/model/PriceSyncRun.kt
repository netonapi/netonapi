package model

import kotlinx.serialization.Serializable
import neton.database.annotations.*

/** 定时价源检查的一次结果（V019）。只记差异数量，不写价格表；运营在「校准官方价」里确认后才写入。 */
@Serializable
@Table("gateway_price_sync_runs")
data class PriceSyncRun(
    @Id val id: Long = 0,
    val checkedAt: Long,
    val source: String? = null,
    val changed: Int = 0,
    val newModels: Int = 0,
    val unparsable: Int = 0,
    val error: String? = null,
)
