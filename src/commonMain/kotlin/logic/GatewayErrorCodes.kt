package logic

import neton.core.http.ValidationError
import neton.core.http.ValidationException

/**
 * module-gateway 的业务错误机器码（Neton 信封里的 `message`）。
 *
 * 规则：业务错误的 message 只放这里的常量；细节放字段级 `ValidationError(path, CODE, CODE)`。
 * 前端按 `error.<CODE>` 翻译（neton-application-front-gateway/src/locales）。
 * 规范：neton-docs/docs/zh-hans/spec/application/gateway-errors.md。
 *
 * ⚠️ 只管 Neton 信封。OpenAI / Anthropic / Gemini 兼容转发端点（/v1/…）返回的是厂商格式的
 * `{"error":{"message":…,"type":…}}`，那是协议契约，不走这里。
 */
object GatewayErrorCodes {
    // ── 渠道 ──
    /** 渠道不存在。 */
    const val CHANNEL_NOT_FOUND = "CHANNEL_NOT_FOUND"
    /** 渠道类型未知（没有对应的上游适配器）。 */
    const val CHANNEL_TYPE_UNKNOWN = "CHANNEL_TYPE_UNKNOWN"
    /** 渠道没有可用的 Key。 */
    const val CHANNEL_NO_AVAILABLE_KEY = "CHANNEL_NO_AVAILABLE_KEY"
    /** 探测时未指定模型且渠道没有模型清单。字段 model。 */
    const val CHANNEL_MODELS_EMPTY = "CHANNEL_MODELS_EMPTY"
    /** 能力清单为空。字段 capabilities。 */
    const val CHANNEL_CAPABILITIES_REQUIRED = "CHANNEL_CAPABILITIES_REQUIRED"
    /** 能力清单含未知能力名。字段 capabilities。 */
    const val CHANNEL_CAPABILITY_UNKNOWN = "CHANNEL_CAPABILITY_UNKNOWN"
    /** 跨协议损耗策略不是 allow/safe/strict。字段 lossPolicy。 */
    const val CHANNEL_LOSS_POLICY_INVALID = "CHANNEL_LOSS_POLICY_INVALID"

    // ── 上游地址（SSRF：写入时字段 baseUrl / proxyUrl / apiKey 的 token_uri；请求时探测 / 拉模型结果的 errorCode）──
    const val UPSTREAM_URL_EMPTY = "UPSTREAM_URL_EMPTY"
    /** 不是 http / https（服务账号 token_uri 只允许 https）。 */
    const val UPSTREAM_URL_SCHEME_INVALID = "UPSTREAM_URL_SCHEME_INVALID"
    const val UPSTREAM_URL_USERINFO_NOT_ALLOWED = "UPSTREAM_URL_USERINFO_NOT_ALLOWED"
    const val UPSTREAM_URL_HOST_EMPTY = "UPSTREAM_URL_HOST_EMPTY"
    /** 端口不在运维出站策略 `[egress] allowed_ports` 里（默认 80、443），或不是合法端口。 */
    const val UPSTREAM_URL_PORT_FORBIDDEN = "UPSTREAM_URL_PORT_FORBIDDEN"
    /** 地址（字面量或解析结果之一）不在公网，且运维出站策略没有放行（回环 / 内网 / 链路本地 / 云元数据…）。 */
    const val UPSTREAM_URL_PRIVATE_ADDRESS = "UPSTREAM_URL_PRIVATE_ADDRESS"
    /** 域名解析不出地址。 */
    const val UPSTREAM_URL_UNRESOLVABLE = "UPSTREAM_URL_UNRESOLVABLE"
    /** 代理地址不在公网且运维出站策略没有放行，或解析不出地址。字段 proxyUrl。 */
    const val UPSTREAM_PROXY_FORBIDDEN = "UPSTREAM_PROXY_FORBIDDEN"
    /** Key 以 `{` 开头却不是合法的服务账号 JSON 对象。字段 apiKey。 */
    const val CHANNEL_KEY_SERVICE_ACCOUNT_INVALID = "CHANNEL_KEY_SERVICE_ACCOUNT_INVALID"

    // ── 价格 ──
    const val PRICE_NOT_FOUND = "PRICE_NOT_FOUND"
    /** 开放范围只能是 ALL / RULES_ONLY。 */
    const val PRICE_ACCESS_INVALID = "PRICE_ACCESS_INVALID"
    /** 定价规则的主体类型只能是 GROUP / USER。 */
    const val PRICE_RULE_SUBJECT_INVALID = "PRICE_RULE_SUBJECT_INVALID"
    /** 定价方式只能是 PLATFORM / OFFICIAL_DISCOUNT / FIXED。 */
    const val PRICE_RULE_MODE_INVALID = "PRICE_RULE_MODE_INVALID"
    /** 官方价折扣必须是 (0, 100] 的倍率。 */
    const val PRICE_RULE_DISCOUNT_INVALID = "PRICE_RULE_DISCOUNT_INVALID"
    /** 固定价要填输入与输出价（按次计价的模型填按次价），且价格合法。 */
    const val PRICE_RULE_PRICES_INVALID = "PRICE_RULE_PRICES_INVALID"
    /** 有长上下文分档的模型不能用固定价，用官方价折扣。 */
    const val PRICE_RULE_FIXED_TIERED = "PRICE_RULE_FIXED_TIERED"
    /** 规则价在某条实际会走的渠道上低于成本。 */
    const val MODEL_PRICE_BELOW_COST = "MODEL_PRICE_BELOW_COST"
    /** 设定服务上游时没给模型名。字段 model。 */
    const val MODEL_REQUIRED = "MODEL_REQUIRED"
    /** 价格不是非负十进制数。字段为价格字段名。 */
    const val PRICE_INVALID = "PRICE_INVALID"
    /** 分档价格不合法：不是数组、少于两档、门槛不严格递增、中间档没有门槛或价格非法。 */
    const val PRICE_TIERS_INVALID = "PRICE_TIERS_INVALID"
    /** 渠道成本来源只能是 official / upstream / manual。 */
    const val CHANNEL_COST_SOURCE_INVALID = "CHANNEL_COST_SOURCE_INVALID"
    /** 新建渠道必须选成本来源（不替管理员默认成「官方价 × 折扣」）。 */
    const val CHANNEL_COST_SOURCE_REQUIRED = "CHANNEL_COST_SOURCE_REQUIRED"
    /** 成本折扣必填：上游几乎不可能按官方价 1:1 计费，不替管理员默认成 1。 */
    const val CHANNEL_COST_DISCOUNT_REQUIRED = "CHANNEL_COST_DISCOUNT_REQUIRED"
    /** 成本来源是上游价目接口时，上游分组倍率必填。 */
    const val CHANNEL_UPSTREAM_GROUP_RATIO_REQUIRED = "CHANNEL_UPSTREAM_GROUP_RATIO_REQUIRED"
    /** 成本来源是上游价目接口时，充值汇率必填。 */
    const val CHANNEL_UPSTREAM_CREDIT_RATE_REQUIRED = "CHANNEL_UPSTREAM_CREDIT_RATE_REQUIRED"
    /** 成本来源为上游价目接口时必须选价目格式（newapi / openrouter）。 */
    const val CHANNEL_PRICING_FORMAT_REQUIRED = "CHANNEL_PRICING_FORMAT_REQUIRED"
    /** 倍率 / 折扣不是十进制数。 */
    const val RATIO_INVALID = "RATIO_INVALID"
    /** 倍率 / 折扣不在 (0, 100]。 */
    const val RATIO_OUT_OF_RANGE = "RATIO_OUT_OF_RANGE"
    /** saleOverride 不是 JSON 对象。 */
    const val SALE_OVERRIDE_INVALID = "SALE_OVERRIDE_INVALID"
    /** saleOverride.perRequest 不是非负整数或 null。 */
    const val SALE_PER_REQUEST_INVALID = "SALE_PER_REQUEST_INVALID"
    /** 售价低于毛利底线（官方成本 × 毛利下限倍率）。 */
    const val SALE_PRICE_BELOW_COST_FLOOR = "SALE_PRICE_BELOW_COST_FLOOR"
    /** 未配置 NETONAPI_PRICE_SYNC_URL，不能立即同步。 */
    const val PRICE_SYNC_NOT_CONFIGURED = "PRICE_SYNC_NOT_CONFIGURED"

    // ── 计费组 ──
    const val GROUP_NOT_FOUND = "GROUP_NOT_FOUND"
    const val GROUP_CODE_REQUIRED = "GROUP_CODE_REQUIRED"
    const val GROUP_CODE_TOO_LONG = "GROUP_CODE_TOO_LONG"
    /** code 只允许字母、数字、`_`、`-`。 */
    const val GROUP_CODE_INVALID_FORMAT = "GROUP_CODE_INVALID_FORMAT"
    const val GROUP_CODE_ALREADY_EXISTS = "GROUP_CODE_ALREADY_EXISTS"
    /** code 创建后不可改。 */
    const val GROUP_CODE_IMMUTABLE = "GROUP_CODE_IMMUTABLE"
    /** 引用的计费组不存在或已删除（写例外时）。 */
    const val GROUP_CODE_UNKNOWN = "GROUP_CODE_UNKNOWN"
    /** 仍有令牌把该组用作分组覆盖。 */
    const val GROUP_IN_USE_BY_TOKEN = "GROUP_IN_USE_BY_TOKEN"
    /** 仍有用户级例外指向该组。 */
    const val GROUP_IN_USE_BY_OVERRIDE = "GROUP_IN_USE_BY_OVERRIDE"
    /** 仍有渠道的分组清单里列着该组。 */
    const val GROUP_IN_USE_BY_CHANNEL = "GROUP_IN_USE_BY_CHANNEL"
    /** 会员组已映射到另一个计费组。 */
    const val MEMBER_GROUP_ALREADY_MAPPED = "MEMBER_GROUP_ALREADY_MAPPED"

    // ── 用户级计费例外 ──
    const val GROUP_OVERRIDE_NOT_FOUND = "GROUP_OVERRIDE_NOT_FOUND"
    /** 该用户已有一条例外，应改用更新。 */
    const val GROUP_OVERRIDE_ALREADY_EXISTS = "GROUP_OVERRIDE_ALREADY_EXISTS"
    const val USER_ID_INVALID = "USER_ID_INVALID"
    /** userId 不是可计费会员（id 写错，或会员已停用 / 注销）。 */
    const val MEMBER_NOT_CHARGEABLE = "MEMBER_NOT_CHARGEABLE"
    const val REMARK_TOO_LONG = "REMARK_TOO_LONG"

    // ── 额度 ──
    /** 发放金额必须是正数（撤回请用 revoke）。 */
    const val QUOTA_GRANT_AMOUNT_INVALID = "QUOTA_GRANT_AMOUNT_INVALID"
    /** 撤回金额必须是正数。 */
    const val QUOTA_REVOKE_AMOUNT_INVALID = "QUOTA_REVOKE_AMOUNT_INVALID"
    /** 撤回必须带 ref 做幂等键。 */
    const val QUOTA_REVOKE_REF_REQUIRED = "QUOTA_REVOKE_REF_REQUIRED"

    // ── 充值 ──
    /** 充值请求校验失败（字段错误见 errors）。 */
    const val RECHARGE_INVALID = "RECHARGE_INVALID"
    /** 在线充值未开通（部署没配充值汇率 NETONAPI_QUOTA_PER_PRICE_UNIT）：控制台提示联系客户经理。 */
    const val RECHARGE_UNAVAILABLE = "RECHARGE_UNAVAILABLE"
    const val RECHARGE_PRICE_INVALID = "RECHARGE_PRICE_INVALID"
    /** 低于后台设置的单笔下限（gateway.recharge.min_price）。 */
    const val RECHARGE_PRICE_TOO_LOW = "RECHARGE_PRICE_TOO_LOW"
    /** 高于后台设置的单笔上限（gateway.recharge.max_price）。 */
    const val RECHARGE_PRICE_TOO_HIGH = "RECHARGE_PRICE_TOO_HIGH"
    /** 必须指定可用的支付渠道（payment 没有默认渠道）。 */
    const val RECHARGE_CHANNEL_REQUIRED = "RECHARGE_CHANNEL_REQUIRED"
    /** 充值单不存在（不是自己的单同样回这个）。 */
    const val RECHARGE_NOT_FOUND = "RECHARGE_NOT_FOUND"
    const val RECHARGE_AMOUNT_INVALID = "RECHARGE_AMOUNT_INVALID"

    // ── 我的令牌 ──
    const val TOKEN_NOT_FOUND = "TOKEN_NOT_FOUND"
    const val TOKEN_GROUP_NOT_ALLOWED = "TOKEN_GROUP_NOT_ALLOWED"
    const val TOKEN_GROUP_LOCKED = "TOKEN_GROUP_LOCKED"
    const val TOKEN_LIMIT_INVALID = "TOKEN_LIMIT_INVALID"
    const val TOKEN_NAME_REQUIRED = "TOKEN_NAME_REQUIRED"
    const val TOKEN_QUOTA_BUDGET_INVALID = "TOKEN_QUOTA_BUDGET_INVALID"
    /** 过期时间必须晚于当前时刻。 */
    const val TOKEN_EXPIRES_AT_INVALID = "TOKEN_EXPIRES_AT_INVALID"
    const val TOKEN_STATUS_INVALID = "TOKEN_STATUS_INVALID"

    // ── 兑换码 ──
    const val REDEMPTION_QUOTA_INVALID = "REDEMPTION_QUOTA_INVALID"
    const val REDEMPTION_COUNT_INVALID = "REDEMPTION_COUNT_INVALID"
    /** 单次生成超过上限（RedemptionCodeLogic.MAX_BATCH）。 */
    const val REDEMPTION_COUNT_TOO_LARGE = "REDEMPTION_COUNT_TOO_LARGE"
    /** 过期时间必须晚于当前时刻。 */
    const val REDEMPTION_EXPIRES_AT_INVALID = "REDEMPTION_EXPIRES_AT_INVALID"
    const val REDEMPTION_NOTE_TOO_LONG = "REDEMPTION_NOTE_TOO_LONG"
    const val REDEMPTION_CODE_REQUIRED = "REDEMPTION_CODE_REQUIRED"
    const val REDEMPTION_CODE_INVALID_FORMAT = "REDEMPTION_CODE_INVALID_FORMAT"
    /** 兑换码不存在 / 无效。 */
    const val REDEMPTION_CODE_NOT_FOUND = "REDEMPTION_CODE_NOT_FOUND"
    const val REDEMPTION_CODE_USED = "REDEMPTION_CODE_USED"
    const val REDEMPTION_CODE_DISABLED = "REDEMPTION_CODE_DISABLED"
    const val REDEMPTION_CODE_EXPIRED = "REDEMPTION_CODE_EXPIRED"
    const val REDEMPTION_BATCH_ID_REQUIRED = "REDEMPTION_BATCH_ID_REQUIRED"
    const val REDEMPTION_BATCH_NOT_FOUND = "REDEMPTION_BATCH_NOT_FOUND"
}

/**
 * 字段级 400：`message` 与字段错误的 `message`/`code` 都是同一个机器码。
 * ErrorMessageGuardTest 把 `rejectField` 当作构造器检查调用点：第 2 个实参必须是 [GatewayErrorCodes] 常量。
 */
fun rejectField(path: String, code: String): Nothing =
    throw ValidationException(listOf(ValidationError(path, code, code)), code)
