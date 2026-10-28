package logic

import kotlinx.serialization.Serializable
import model.GatewayToken
import neton.database.dsl.*
import table.GatewayGroupTable
import table.GatewayTokenTable

@Serializable
data class PriceItemsVO(
    /** USD / 1M tokens */
    val input: String,
    val output: String,
    val cacheRead: String,
    val cacheWrite: String,
    /** 按次计价（μUSD / 次）；非空时 token 价格不生效 */
    val perRequest: Long? = null,
) {
    companion object {
        fun of(p: model.ModelPrice) = PriceItemsVO(p.inputPrice, p.outputPrice, p.cacheReadPrice, p.cacheWritePrice, p.perRequestPrice)
    }
}

/** 价目表的一行：官方价、实付价、相当于官方价几折、价格来源。 */
@Serializable
data class PricebookRowVO(
    val model: String,
    val displayName: String? = null,
    val official: PriceItemsVO,
    val price: PriceItemsVO,
    /** 实付 / 官方，万分位（7000 = 官方价 7 折）；官方价为 0 时为空 */
    val discountBp: Long? = null,
    /** USER（用户规则）/ GROUP（分组规则）/ PLATFORM（平台价 × 分组倍率） */
    val source: String,
    /** 命中规则时的定价方式 */
    val mode: String? = null,
    /** 有长上下文分档（价格是第一档；长输入按更贵的档计） */
    val tiered: Boolean = false,
    val access: String = PriceBook.ACCESS_ALL,
    val defaultMaxOutputTokens: Int? = null,
    /** 最差毛利的组合（只有管理端给） */
    val worst: PricingCombo? = null,
)

@Serializable
data class PricebookGroupVO(
    val groupCode: String,
    val groupName: String,
    val groupRatio: String,
    val rows: List<PricebookRowVO>,
)

/** 用户价目表的视图（管理台「用户定价」、控制台「模型与价格」共用）。 */
object PricebookView {

    /** 这个用户的 Key 所在的计费分组（没绑 = default）；一把 Key 都没有时给 default。 */
    suspend fun groupsOf(userId: Long): List<String> {
        val codes = GatewayTokenTable.query { where { GatewayToken::userId eq userId } }.list()
            .map { it.groupOverride?.takeIf { g -> g.isNotBlank() } ?: BillingGroupResolver.DEFAULT_CODE }
            .distinct()
        return codes.ifEmpty { listOf(BillingGroupResolver.DEFAULT_CODE) }
    }

    suspend fun build(userId: Long?, groupCodes: List<String>, check: PricingCheckLogic? = null): List<PricebookGroupVO> {
        val groups = GatewayGroupTable.findAll().associateBy { it.code }
        return groupCodes.mapNotNull { code ->
            val g = groups[code] ?: return@mapNotNull null
            val rows = PriceBook.list(userId, code).map { q ->
                PricebookRowVO(
                    model = q.model,
                    displayName = q.official.displayName,
                    official = PriceItemsVO.of(q.official),
                    price = PriceItemsVO.of(PriceBook.effective(q)),
                    discountBp = PriceBook.discountBp(q),
                    source = q.source,
                    mode = q.rule?.mode,
                    tiered = !q.official.tiers.isNullOrBlank(),
                    access = q.official.access,
                    defaultMaxOutputTokens = q.official.defaultMaxOutputTokens,
                    worst = check?.combosFor(q, setOf(code))?.firstOrNull(),
                )
            }
            PricebookGroupVO(g.code, g.name, g.ratio, rows)
        }
    }
}
