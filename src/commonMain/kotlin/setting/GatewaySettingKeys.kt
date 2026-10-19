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

    val definitions: List<SettingDefinition<*>> = listOf(RECHARGE_MIN_PRICE, RECHARGE_MAX_PRICE)
}
