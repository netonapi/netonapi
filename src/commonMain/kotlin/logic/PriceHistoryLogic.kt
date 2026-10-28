package logic

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import model.ModelPrice
import model.PriceHistory
import neton.database.dsl.*
import table.PriceHistoryTable

/**
 * 调价记录：官方价或售价设置真的变了才记，没变的（同步时价源与库里一致）不记，免得记录被噪音淹没。
 * 写记录失败不影响调价本身 —— 记录是辅助对账，不能因为它让运营改不了价。
 */
object PriceHistoryLogic {

    suspend fun record(action: String, before: ModelPrice?, after: ModelPrice?, operatorId: Long? = null) {
        val b = before?.let { snapshot(it) }
        val a = after?.let { snapshot(it) }
        if (b != null && b == a) return
        val model = after?.model ?: before?.model ?: return
        runCatching { PriceHistoryTable.insert(PriceHistory(model = model, action = action, before = b, after = a, operatorId = operatorId)) }
    }

    /** 非价格行的变更（定价规则、开放范围）：before / after 直接存给定的文本（JSON 或枚举值）。 */
    suspend fun recordRaw(action: String, model: String, before: String?, after: String?, operatorId: Long? = null) {
        if (before == after) return
        runCatching { PriceHistoryTable.insert(PriceHistory(model = model, action = action, before = before, after = after, operatorId = operatorId)) }
    }

    suspend fun list(model: String, max: Int = 50): List<PriceHistory> =
        PriceHistoryTable.query {
            where { PriceHistory::model eq model }
            orderBy(PriceHistory::id.desc())
            limitOffset(max, 0)
        }.list()

    /** 只取影响计价的字段，顺序固定，便于比对「到底变没变」。 */
    fun snapshot(p: ModelPrice): String = buildJsonObject {
        put("input", p.inputPrice)
        put("output", p.outputPrice)
        put("cacheRead", p.cacheReadPrice)
        put("cacheWrite", p.cacheWritePrice)
        put("perRequest", p.perRequestPrice?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?))
        put("saleOverride", p.saleOverride)
        put("tiers", PricingTiers.normalize(p.tiers))
    }.toString()
}
