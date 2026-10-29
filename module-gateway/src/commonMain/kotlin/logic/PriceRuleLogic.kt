package logic

import model.PriceRule
import neton.database.dsl.*
import table.PriceRuleTable

/** 定价规则的存取（spec §4.3）。写入后调用方负责清 [PriceBook] 缓存。 */
object PriceRuleLogic {

    suspend fun list(subjectType: String, subjectId: String): List<PriceRule> =
        PriceRuleTable.query {
            where { and(PriceRule::subjectType eq subjectType, PriceRule::subjectId eq subjectId) }
            orderBy(PriceRule::model.asc())
        }.list()

    suspend fun find(subjectType: String, subjectId: String, model: String): PriceRule? =
        PriceRuleTable.oneWhere {
            and(PriceRule::subjectType eq subjectType, PriceRule::subjectId eq subjectId, PriceRule::model eq model)
        }

    suspend fun upsert(rule: PriceRule): Pair<PriceRule?, PriceRule> {
        val current = find(rule.subjectType, rule.subjectId, rule.model)
        val saved = if (current == null) PriceRuleTable.insert(rule) else rule.copy(id = current.id, createdAt = current.createdAt).also { PriceRuleTable.update(it) }
        return current to saved
    }

    suspend fun delete(subjectType: String, subjectId: String, model: String): PriceRule? {
        val current = find(subjectType, subjectId, model) ?: return null
        PriceRuleTable.destroy(current.id)
        return current
    }

    suspend fun deleteByModel(model: String) {
        PriceRuleTable.query { where { PriceRule::model eq model } }.list().forEach { PriceRuleTable.destroy(it.id) }
    }

    suspend fun deleteBySubject(subjectType: String, subjectId: String) {
        list(subjectType, subjectId).forEach { PriceRuleTable.destroy(it.id) }
    }

    /** 调价记录里的规则快照（只取影响计价的字段，顺序固定）。 */
    fun snapshot(r: PriceRule): String = kotlinx.serialization.json.buildJsonObject {
        put("subject", kotlinx.serialization.json.JsonPrimitive("${r.subjectType}:${r.subjectId}"))
        put("mode", kotlinx.serialization.json.JsonPrimitive(r.mode))
        put("discount", kotlinx.serialization.json.JsonPrimitive(r.discount))
        put("prices", kotlinx.serialization.json.JsonPrimitive(r.prices))
    }.toString()
}
