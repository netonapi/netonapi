package setting

/**
 * gateway 模块的全局配置定义（SYSTEM_CONFIG_SPEC），后台「配置管理」里可改，改完即生效。
 */
object GatewaySettingKeys {

    const val CATEGORY = "gateway"

    /**
     * 单笔充值下限（支付金额的最小单位，人民币即「分」）。
     * 太小的单子手续费可能比金额还高，也是刷单、测卡的常见手法。
     */
    val RECHARGE_MIN_PRICE: SettingDefinition<Long> = SettingDefinition.long(
        category = CATEGORY,
        key = "gateway.recharge.min_price",
        default = 100,
        name = "单笔充值下限(分)",
        description = "用户单笔充值的最低金额，单位是支付金额的最小单位（人民币为分）。默认 100 即 1 元。",
        min = 1,
    )

    /**
     * 单笔充值上限。大额更可能是盗刷或输错，超过的让用户分笔或走线下。
     */
    val RECHARGE_MAX_PRICE: SettingDefinition<Long> = SettingDefinition.long(
        category = CATEGORY,
        key = "gateway.recharge.max_price",
        default = 1_000_000,
        name = "单笔充值上限(分)",
        description = "用户单笔充值的最高金额，单位同上。默认 1000000 即 1 万元；须不低于下限，否则以下限为准。",
        min = 1,
    )

    /**
     * 全站加价率：售价 = 官方价 × 它（单个模型可以单独设，见价格页）。
     * 默认取环境变量 NEWGATE_SALE_MARKUP（旧部署的配置照常生效），都没配就是 1，即按官方价卖。
     */
    val PRICING_MARKUP: SettingDefinition<String> = SettingDefinition.decimal(
        category = CATEGORY,
        key = "gateway.pricing.markup",
        default = envDecimal("NEWGATE_SALE_MARKUP") ?: "1",
        name = "全站加价率",
        description = "售价 = 官方价 × 加价率。1.2 表示在官方价基础上加 20%；单个模型可在「模型定价」里单独设置。取值 (0, 100]。",
    )

    /**
     * 最低毛利：任何组合下的实收都不得低于「成本 × 它」，否则价格保存不了、定价体检标红。
     * 默认取环境变量 NEWGATE_MIN_MARGIN，都没配就是 1，即不许亏本卖。
     */
    val PRICING_MIN_MARGIN: SettingDefinition<String> = SettingDefinition.decimal(
        category = CATEGORY,
        key = "gateway.pricing.min_margin",
        default = envDecimal("NEWGATE_MIN_MARGIN") ?: "1",
        name = "最低毛利倍率",
        description = "实收不得低于成本 × 该值。1 = 不亏本，1.1 = 至少 10% 毛利。取值 (0, 100]。",
    )

    /** 校准官方价用的价源格式：litellm（默认）/ openrouter / netonapi（与批量导入同格式）。 */
    val PRICING_SOURCE_FORMAT: SettingDefinition<String> = SettingDefinition.string(
        category = CATEGORY,
        key = "gateway.pricing.source_format",
        default = "litellm",
        name = "官方价价源格式",
        description = "litellm：LiteLLM 公开价目表（模型名基本就是官方名，推荐）；openrouter：OpenRouter /api/v1/models；netonapi：与批量导入相同的格式。",
        maxLength = 16,
    )

    val PRICING_SOURCE_URL: SettingDefinition<String> = SettingDefinition.string(
        category = CATEGORY,
        key = "gateway.pricing.source_url",
        default = "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json",
        name = "官方价价源地址",
        description = "「模型定价 → 校准官方价」从这里拉取价目表。OpenRouter 填 https://openrouter.ai/api/v1/models。",
        maxLength = 512,
    )

    private fun envDecimal(name: String): String? =
        neton.core.config.getEnv(name)?.trim()?.takeIf { it.matches(Regex("^\\d+(\\.\\d+)?$")) }

    val definitions: List<SettingDefinition<*>> = listOf(RECHARGE_MIN_PRICE, RECHARGE_MAX_PRICE, PRICING_MARKUP, PRICING_MIN_MARGIN, PRICING_SOURCE_FORMAT, PRICING_SOURCE_URL)
}
