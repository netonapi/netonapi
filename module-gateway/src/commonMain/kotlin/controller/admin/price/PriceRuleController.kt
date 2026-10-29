package controller.admin.price

import kotlinx.serialization.Serializable
import logic.GatewayErrorCodes
import logic.MemberDirectory
import logic.PriceBook
import logic.PriceItemsVO
import logic.PriceRuleLogic
import logic.PricebookGroupVO
import logic.PricebookView
import logic.PricingCheckLogic
import logic.PricingCombo
import logic.PricingPolicy
import logic.rejectField
import model.GatewayGroup
import model.ModelPrice
import model.PriceRule
import neton.core.annotations.*
import neton.core.http.HttpException
import neton.core.http.NetonErrorCode
import neton.core.http.ValidationError
import neton.database.dsl.*
import table.GatewayGroupTable
import table.ModelPriceTable

@Serializable
data class SavePriceRuleRequest(
    /** GROUP / USER */
    val subjectType: String,
    /** 分组 code 或用户 ID */
    val subjectId: String,
    val model: String,
    /** PLATFORM / OFFICIAL_DISCOUNT / FIXED */
    val mode: String,
    /** 官方价折扣（如 "0.7"）；OFFICIAL_DISCOUNT 必填 */
    val discount: String? = null,
    /** 固定价 JSON（与 sale_override 同结构，如 {"input":"2","output":"8"}）；FIXED 必填 */
    val prices: String? = null,
    val remark: String? = null,
)

@Serializable
data class PriceRuleVO(
    val id: Long,
    val subjectType: String,
    val subjectId: String,
    val model: String,
    val mode: String,
    val discount: String? = null,
    val prices: String? = null,
    val remark: String? = null,
    val updatedAt: Long? = null,
    val official: PriceItemsVO? = null,
    /** 按这条规则的实付价（PLATFORM 按第一个相关分组的倍率） */
    val price: PriceItemsVO? = null,
    val discountBp: Long? = null,
    val status: String = STATUS_UNSERVED,
    val worst: PricingCombo? = null,
)

/** 规则在实际会走的渠道上的毛利预览：OK / THIN（偏薄）/ LOSS（亏本，不能保存）/ UNSERVED（暂无渠道承载）。 */
@Serializable
data class PriceRulePreviewVO(
    val price: PriceItemsVO,
    val discountBp: Long? = null,
    val status: String,
    val worst: PricingCombo? = null,
    /** 最差的若干组合 */
    val combos: List<PricingCombo> = emptyList(),
    /** 核算用的计费分组（用户规则 = 他的 Key 所在分组） */
    val groups: List<String> = emptyList(),
)

const val STATUS_OK = "OK"
const val STATUS_THIN = "THIN"
const val STATUS_LOSS = "LOSS"
const val STATUS_UNSERVED = "UNSERVED"

/**
 * 定价规则（docs/gateway-pricing-spec.md §4.3）：「分组或用户 × 模型」的约定价，以官方价为锚。
 *
 * 保存前按真实会发生的组合核算毛利（分组规则 = 该分组；用户规则 = 他的 Key 当前所在分组）：
 * 任一渠道亏本拒绝保存（[GatewayErrorCodes.MODEL_PRICE_BELOW_COST]），偏薄照存但在结果里标出。
 */
@Controller("/gateway/price-rule")
class PriceRuleController(private val pricingCheckLogic: PricingCheckLogic) {

    @Get("/list")
    @Permission("gateway:price:query")
    suspend fun list(@Query subjectType: String, @Query subjectId: String): List<PriceRuleVO> {
        val type = requireSubjectType(subjectType)
        val groups = groupsFor(type, subjectId.trim())
        val prices = ModelPriceTable.findAll().associateBy { it.model }
        val markup = PricingPolicy.current().markup
        return PriceRuleLogic.list(type, subjectId.trim()).map { r ->
            val official = prices[r.model]
            val base = PriceRuleVO(r.id, r.subjectType, r.subjectId, r.model, r.mode, r.discount, r.prices, r.remark, r.updatedAt)
            if (official == null || groups.isEmpty()) return@map base
            val preview = runCatching { evaluate(r, official, groups, markup) }.getOrNull() ?: return@map base
            base.copy(official = PriceItemsVO.of(official), price = preview.price, discountBp = preview.discountBp, status = preview.status, worst = preview.worst)
        }
    }

    /** 不保存，只算：管理台编辑时实时显示最终价、相当于官方几折与最差毛利。 */
    @Post("/preview")
    @Permission("gateway:price:query")
    suspend fun preview(@Body request: SavePriceRuleRequest): PriceRulePreviewVO {
        val (rule, official, groups) = validate(request)
        return evaluate(rule, official, groups, PricingPolicy.current().markup)
    }

    @Put("/save")
    @Permission("gateway:price:update")
    suspend fun save(@Body request: SavePriceRuleRequest, identity: neton.core.interfaces.Identity): PriceRulePreviewVO {
        val (rule, official, groups) = validate(request)
        val preview = evaluate(rule, official, groups, PricingPolicy.current().markup)
        // 亏本拒绝：哪条渠道亏、亏多少由 /preview 给出（管理台保存前先预览），错误本身只带机器码
        if (preview.status == STATUS_LOSS) {
            throw HttpException(
                NetonErrorCode.OPERATION_CONFLICT, GatewayErrorCodes.MODEL_PRICE_BELOW_COST,
                listOf(ValidationError("price", GatewayErrorCodes.MODEL_PRICE_BELOW_COST, GatewayErrorCodes.MODEL_PRICE_BELOW_COST)),
            )
        }
        val (before, after) = PriceRuleLogic.upsert(rule)
        logic.PriceHistoryLogic.recordRaw(
            "rule_set", rule.model, before?.let { PriceRuleLogic.snapshot(it) }, PriceRuleLogic.snapshot(after), identity.id.toLongOrNull(),
        )
        invalidate(rule.subjectType, rule.subjectId)
        return preview
    }

    @Delete("/delete")
    @Permission("gateway:price:update")
    suspend fun delete(@Query subjectType: String, @Query subjectId: String, @Query model: String, identity: neton.core.interfaces.Identity) {
        val type = requireSubjectType(subjectType)
        val removed = PriceRuleLogic.delete(type, subjectId.trim(), model.trim()) ?: return
        logic.PriceHistoryLogic.recordRaw("rule_delete", removed.model, PriceRuleLogic.snapshot(removed), null, identity.id.toLongOrNull())
        invalidate(type, subjectId.trim())
    }

    /** 某个用户在他各计费分组下的价目表（含最差毛利）。 */
    @Get("/pricebook")
    @Permission("gateway:price:query")
    suspend fun pricebook(@Query userId: Long): List<PricebookGroupVO> {
        if (userId <= 0) rejectField("userId", GatewayErrorCodes.MEMBER_NOT_CHARGEABLE)
        return PricebookView.build(userId, PricebookView.groupsOf(userId), pricingCheckLogic)
    }

    private fun invalidate(subjectType: String, subjectId: String) {
        if (subjectType == PriceBook.SUBJECT_USER) subjectId.toLongOrNull()?.let { PriceBook.invalidateUser(it) } else PriceBook.invalidate()
    }

    private fun requireSubjectType(raw: String): String {
        val t = raw.trim().uppercase()
        if (t != PriceBook.SUBJECT_GROUP && t != PriceBook.SUBJECT_USER) rejectField("subjectType", GatewayErrorCodes.PRICE_RULE_SUBJECT_INVALID)
        return t
    }

    /** 核算毛利用的分组：分组规则 = 这个分组；用户规则 = 他的 Key 所在分组。 */
    private suspend fun groupsFor(type: String, subjectId: String): List<GatewayGroup> {
        val all = GatewayGroupTable.findAll().associateBy { it.code }
        val codes = if (type == PriceBook.SUBJECT_GROUP) listOf(subjectId)
            else subjectId.toLongOrNull()?.let { PricebookView.groupsOf(it) }.orEmpty()
        return codes.mapNotNull { all[it] }
    }

    private suspend fun validate(r: SavePriceRuleRequest): Triple<PriceRule, ModelPrice, List<GatewayGroup>> {
        val type = requireSubjectType(r.subjectType)
        val subject = r.subjectId.trim()
        if (type == PriceBook.SUBJECT_GROUP) {
            if (GatewayGroupTable.oneWhere { GatewayGroup::code eq subject } == null) rejectField("subjectId", GatewayErrorCodes.GROUP_CODE_UNKNOWN)
        } else {
            val uid = subject.toLongOrNull()?.takeIf { it > 0 } ?: rejectField("subjectId", GatewayErrorCodes.MEMBER_NOT_CHARGEABLE)
            MemberDirectory.port?.let { if (!it.isChargeableMember(uid)) rejectField("subjectId", GatewayErrorCodes.MEMBER_NOT_CHARGEABLE) }
        }
        val model = r.model.trim()
        val official = ModelPriceTable.oneWhere { ModelPrice::model eq model }
            ?: throw HttpException(NetonErrorCode.RESOURCE_NOT_FOUND, GatewayErrorCodes.PRICE_NOT_FOUND)
        val mode = r.mode.trim().uppercase()
        if (mode !in PriceBook.MODES) rejectField("mode", GatewayErrorCodes.PRICE_RULE_MODE_INVALID)
        val discount = if (mode == PriceBook.MODE_OFFICIAL_DISCOUNT) {
            runCatching { logic.PricingCalc.requireRatio(r.discount?.trim() ?: "") }.getOrNull()
                ?: rejectField("discount", GatewayErrorCodes.PRICE_RULE_DISCOUNT_INVALID)
        } else null
        val prices = if (mode == PriceBook.MODE_FIXED) {
            if (!official.tiers.isNullOrBlank()) rejectField("prices", GatewayErrorCodes.PRICE_RULE_FIXED_TIERED)
            val raw = r.prices?.trim()?.takeIf { it.isNotEmpty() } ?: rejectField("prices", GatewayErrorCodes.PRICE_RULE_PRICES_INVALID)
            val obj = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject }.getOrNull()
                ?: rejectField("prices", GatewayErrorCodes.PRICE_RULE_PRICES_INVALID)
            val complete = if (official.perRequestPrice != null) "perRequest" in obj else "input" in obj && "output" in obj
            if (!complete) rejectField("prices", GatewayErrorCodes.PRICE_RULE_PRICES_INVALID)
            // 与 sale_override 同一套解析：非法价格串、越界在这里就挡下
            runCatching { logic.SalePricing.resolve(official.copy(saleOverride = raw), "1") }.getOrNull()
                ?: rejectField("prices", GatewayErrorCodes.PRICE_RULE_PRICES_INVALID)
            raw
        } else null
        val rule = PriceRule(
            subjectType = type, subjectId = subject, model = model, mode = mode, discount = discount, prices = prices,
            remark = r.remark?.trim()?.take(256)?.takeIf { it.isNotEmpty() },
        )
        return Triple(rule, official, groupsFor(type, subject))
    }

    private suspend fun evaluate(rule: PriceRule, official: ModelPrice, groups: List<GatewayGroup>, markup: String): PriceRulePreviewVO {
        val combos = mutableListOf<PricingCombo>()
        var shown: PriceBook.Quote? = null
        for (g in groups) {
            val q = PriceBook.quote(official, g, rule, PriceBook.SOURCE_GROUP, markup)
            if (shown == null) shown = q
            combos += pricingCheckLogic.combosFor(q, setOf(g.code))
        }
        val q = shown ?: PriceBook.quote(
            official, GatewayGroup(code = logic.BillingGroupResolver.DEFAULT_CODE, name = "", ratio = "1"), rule, PriceBook.SOURCE_GROUP, markup,
        )
        val sorted = combos.sortedWith(compareBy<PricingCombo> { it.marginBp }.thenBy { it.revenue - it.cost })
        val worst = sorted.firstOrNull()
        val status = when {
            worst == null -> STATUS_UNSERVED
            worst.status == "LOSS" -> STATUS_LOSS
            worst.status == "THIN" -> STATUS_THIN
            else -> STATUS_OK
        }
        return PriceRulePreviewVO(
            price = PriceItemsVO.of(PriceBook.effective(q)), discountBp = PriceBook.discountBp(q),
            status = status, worst = worst, combos = sorted.take(5), groups = groups.map { it.code },
        )
    }
}
