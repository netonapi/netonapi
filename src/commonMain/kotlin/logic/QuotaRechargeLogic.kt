package logic

import model.GatewayQuotaRecharge
import neton.logging.Logger
import table.GatewayQuotaRechargeTable

/**
 * 网关额度充值：建意图单 → 装配层用 payment 下单 → 支付成功事件回来按订单号入账。
 *
 * 钱包是法币账本、网关额度是 μUSD 账本，两者通过 merchant_order_id 关联而**不共用余额**：
 * 同一笔钱不能既进钱包又进网关额度，否则一次支付发放两份价值。因此本 logic 只认
 * 前缀 [PREFIX] 的订单号，钱包充值的 `wallet:recharge:` 一律不归它管。
 *
 * 幂等有三层，互相独立，任一失效另一层兜底：
 *  ① payment 的 updateSuccess 在 publish 之前按 status 早退 —— 重复回调根本不会二次发布；
 *  ② [markPaid] 里 pay_status 的乐观锁占位 —— 并发/重投只有一个能改成已入账；
 *  ③ 台账 ref 唯一约束（ref = merchant_order_id）—— 即使 ② 被绕过，第二次入账撞约束回滚。
 */
@neton.core.annotations.Logic(logger = "logic.gateway-quota-recharge")
class QuotaRechargeLogic(private val log: Logger, private val quotaLogic: QuotaLogic) {

    /** 下单能力来自装配层（gateway 不认识 payment）。未绑定时充值接口明确报错，不静默降级。 */
    var orderPort: RechargeOrderPort? = null

    data class Created(
        val rechargeId: Long,
        val merchantOrderId: String,
        val amountMicro: Long,
        val price: Long,
        val prepay: RechargePrepay,
    )

    /**
     * 建充值单并下单。
     *
     * 额度由服务端按 [ENV_QUOTA_PER_PRICE_UNIT] 算出，**不接受客户端指定** ——
     * 客户端能同时填 price 与 amount 就等于自己给自己定价。
     * 汇率没配就拒绝：静默按 1:1 发放是白送钱，而且不会留下任何日志痕迹。
     */
    suspend fun create(userId: Long, price: Long, channelCode: String?, userIp: String?): Created {
        require(price > 0) { "price must be positive" }
        val amountMicro = checkedMul(price, quotaPerPriceUnit())

        val intent = GatewayQuotaRechargeTable.insert(
            GatewayQuotaRecharge(userId = userId, amountMicro = amountMicro, price = price)
        )
        val merchantOrderId = merchantOrderId(intent.id)
        val port = orderPort ?: error(
            "module-gateway: no RechargeOrderPort is bound, so quota recharge cannot place an order. " +
                "Bind one in the application; it must create the payment order with the given merchantOrderId.",
        )

        val prepay = try {
            port.createOrder(
                merchantOrderId = merchantOrderId,
                subject = RECHARGE_SUBJECT,
                price = price,
                channelCode = channelCode,
                userIp = userIp,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 协程取消原样上抛
        } catch (e: Throwable) {
            // 下单失败就把意图单关掉：留一张永远付不了的待支付单，对账时只是噪声。
            close(intent.id)
            throw e
        }

        neton.database.dbContext().execute(
            "UPDATE gateway_quota_recharges SET pay_order_id = :oid WHERE id = :id",
            mapOf("oid" to prepay.orderId, "id" to intent.id),
        )
        log.info(
            "gateway quota recharge created id=${intent.id} user=$userId price=$price " +
                "amount=$amountMicro merchantOrderId=$merchantOrderId payOrderId=${prepay.orderId}"
        )
        return Created(intent.id, merchantOrderId, amountMicro, price, prepay)
    }

    /**
     * 用户回读自己的一张充值单；不是他的、或不存在的，一律 null（调用方按 404 处置）。
     *
     * 跳转式支付需要这个：[create] 在 displayMode=REDIRECT_URL 时把用户送出本站，回来后
     * 那个标签页对「回调到底落了没有」一无所知。此前唯一的信号是轮询余额，而余额不动
     * 分不清三件事 —— 还在等、回调失败、单子已被 [close] 关掉。三者的处置分别是等 /
     * 找运营 / 重新下单，混成「余额没变」等于让用户自己猜。
     *
     * 不做 /page 列表：已入账的充值在台账里就是一行 type='recharge'、ref='gateway:quota:{id}'，
     * 要历史去流水页翻；待支付单只活在跳转付款那几分钟，前端把 rechargeId 存进 sessionStorage
     * 就够。再加一个列表端点等于同一份数据有两个真相来源。
     */
    suspend fun findForUser(rechargeId: Long, userId: Long): GatewayQuotaRecharge? =
        visibleTo(GatewayQuotaRechargeTable.get(rechargeId), userId)

    /**
     * 支付成功 → 入账。由装配层的监听者调用（监听 payment 的 PayOrderPaidEvent 会引入编译依赖，
     * 那条依赖正是 [RechargeOrderPort] 要避免的，所以监听者放装配层）。
     *
     * 与 payment 的 markRechargePaid 同构：**先乐观锁占位、再入账**。反过来的话，
     * 入账成功而状态没更新就会卡死 —— 重投时 grant 撞 ref 唯一约束抛错，状态永远停在待支付。
     */
    suspend fun markPaid(rechargeId: Long, payChannelCode: String) {
        val db = neton.database.dbContext()
        db.transaction {
            val recharge = GatewayQuotaRechargeTable.get(rechargeId)
                ?: throw IllegalArgumentException("gateway quota recharge not found: $rechargeId")
            // 幂等：已入账直接返回（回调重投、对账补单都走这里）
            if (recharge.payStatus == PAY_STATUS_PAID) return@transaction
            // 已关闭（过期）的单也收：用户在过期临界点付了款、回调晚到时，钱确实收到了，
            // 这里拒绝就是「收了钱不给额度」。已退款的单不再入账。
            if (recharge.payStatus != PAY_STATUS_WAITING && recharge.payStatus != PAY_STATUS_CLOSED) {
                throw IllegalStateException(
                    "gateway quota recharge $rechargeId cannot be paid (status=${recharge.payStatus})"
                )
            }
            // ② 乐观锁占位：并发第二个回调在这里拿到 0 行 → 退出，不会重复 grant
            val claimed = db.execute(
                "UPDATE gateway_quota_recharges SET pay_status = :paid, pay_channel_code = :cc, paid_at = :ts " +
                    "WHERE id = :id AND pay_status IN (:waiting, :closed)",
                mapOf(
                    "paid" to PAY_STATUS_PAID,
                    "cc" to payChannelCode,
                    "ts" to nowMillis(),
                    "id" to rechargeId,
                    "waiting" to PAY_STATUS_WAITING,
                    "closed" to PAY_STATUS_CLOSED,
                ),
            )
            if (claimed == 0L) return@transaction
            // ③ ref = merchant_order_id：台账上的唯一约束是最后一道闸
            val balance = quotaLogic.grant(
                recharge.userId, recharge.amountMicro, type = "recharge", ref = merchantOrderId(rechargeId),
            )
            log.info(
                "gateway quota recharge paid id=$rechargeId user=${recharge.userId} " +
                    "amount=${recharge.amountMicro} balance=$balance"
            )
        }
    }

    /**
     * 支付单被退款 → 扣回这笔充值发放的额度（按退款比例）。
     *
     * 钱已经退回用户手里，额度必须收回，否则就是「退了钱还能接着用」。余额不够扣时允许扣成负数：
     * 负余额会挡住后续调用，这比让退款失败（钱已经出去了，回滚不了）或少扣更对。
     * 与入账同样三层幂等：状态早退 → pay_status 乐观锁占位 → 台账 ref（[refundRef]）唯一约束。
     * 未入账的单（待支付 / 已关闭）被退款不扣任何额度，只记一条日志 —— 那笔钱本来就没变成额度。
     */
    suspend fun markRefunded(rechargeId: Long, refundPrice: Long, orderPrice: Long) {
        val db = neton.database.dbContext()
        db.transaction {
            val recharge = GatewayQuotaRechargeTable.get(rechargeId)
                ?: throw IllegalArgumentException("gateway quota recharge not found: $rechargeId")
            if (recharge.payStatus == PAY_STATUS_REFUNDED) return@transaction
            if (recharge.payStatus != PAY_STATUS_PAID) {
                log.warn("gateway quota recharge refunded before it was credited id=$rechargeId status=${recharge.payStatus}")
                return@transaction
            }
            val claimed = db.execute(
                "UPDATE gateway_quota_recharges SET pay_status = :refunded WHERE id = :id AND pay_status = :paid",
                mapOf("refunded" to PAY_STATUS_REFUNDED, "id" to rechargeId, "paid" to PAY_STATUS_PAID),
            )
            if (claimed == 0L) return@transaction
            val claw = clawbackMicro(recharge.amountMicro, refundPrice, orderPrice)
            val balance = quotaLogic.debitInTx(recharge.userId, -claw, TYPE_REFUND, refundRef(rechargeId))
            log.info(
                "gateway quota recharge refunded id=$rechargeId user=${recharge.userId} " +
                    "clawback=$claw refund=$refundPrice/$orderPrice balance=$balance"
            )
        }
    }

    /**
     * 关掉一张永远付不了的意图单。
     *
     * `:waiting` 必须真的绑上值：SQL 里有它而 map 里没有，sqlx4k 会抛
     * NamedParameterValueNotSupplied —— 那会在 catch 块里盖掉**下单失败的真实原因**
     * （运维只看到一条 sqlx4k 内部错误，查不到是哪个渠道不可用），同时意图单停在待支付，
     * 正是这个方法本来要消除的那条对账噪声。
     */
    /** 支付单过期关闭 → 充值单跟着关（只关待支付的；晚到的付款仍可经 [markPaid] 入账）。 */
    suspend fun markClosed(rechargeId: Long) = close(rechargeId)

    private suspend fun close(rechargeId: Long) {
        neton.database.dbContext().execute(
            "UPDATE gateway_quota_recharges SET pay_status = :closed WHERE id = :id AND pay_status = :waiting",
            mapOf(
                "closed" to PAY_STATUS_CLOSED,
                "id" to rechargeId,
                "waiting" to PAY_STATUS_WAITING,
            ),
        )
    }

    /** μUSD / 法币最小单位。没配 = 充值不可用，不是「那就按 1:1 发」。 */
    private fun quotaPerPriceUnit(): Long {
        val raw = neton.core.config.getEnv(ENV_QUOTA_PER_PRICE_UNIT)?.trim()
        if (raw.isNullOrEmpty()) {
            error(
                "quota recharge is not configured: set $ENV_QUOTA_PER_PRICE_UNIT " +
                    "(μUSD granted per unit of pay_orders.price)",
            )
        }
        val value = raw.toLongOrNull() ?: error("$ENV_QUOTA_PER_PRICE_UNIT must be an integer, got '$raw'")
        if (value <= 0) error("$ENV_QUOTA_PER_PRICE_UNIT must be positive, got $value")
        return value
    }

    /** Long 乘法在 Kotlin 里溢出是静默回绕的 —— 钱上不允许。 */
    private fun checkedMul(a: Long, b: Long): Long {
        val r = a * b
        if (a != 0L && r / a != b) error("quota amount overflow: $a * $b")
        return r
    }

    private fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    companion object {
        const val PAY_STATUS_WAITING = 0
        const val PAY_STATUS_PAID = 1
        const val PAY_STATUS_CLOSED = 2
        /** 已入账后又被退款，额度已按比例扣回（[markRefunded]）。 */
        const val PAY_STATUS_REFUNDED = 3

        /** 台账类型：充值退款扣回，amount 为负。 */
        const val TYPE_REFUND = "refund"

        /** 退款扣回的台账幂等键；与入账的 ref（`gateway:quota:{id}`）区分开，两者各只能出现一次。 */
        fun refundRef(rechargeId: Long): String = "refund:${merchantOrderId(rechargeId)}"

        /**
         * 按比例扣回的额度：amountMicro × refundPrice ÷ orderPrice，向下取整，退款超过订单金额时按全额封顶。
         * 拆成「整除部分 + 余数部分」计算，避免 μUSD × 分 溢出 Long（溢出是静默回绕的）。
         */
        fun clawbackMicro(amountMicro: Long, refundPrice: Long, orderPrice: Long): Long {
            require(orderPrice > 0) { "orderPrice must be positive" }
            if (refundPrice >= orderPrice) return amountMicro
            if (refundPrice <= 0) return 0
            return (amountMicro / orderPrice) * refundPrice + (amountMicro % orderPrice) * refundPrice / orderPrice
        }

        /** μUSD / 法币最小单位（与 payment 的 pay_orders.price 同单位）。 */
        const val ENV_QUOTA_PER_PRICE_UNIT = "NEWGATE_QUOTA_PER_PRICE_UNIT"

        /**
         * 本业务的商户订单号前缀。监听者只认它 —— 钱包充值用 `wallet:recharge:`，
         * 内容解锁用各自的前缀，同一个"支付成功"事件由多个消费方各取所需。
         */
        const val PREFIX = "gateway:quota:"

        /** 只放 rechargeId：意图单自己记着 userId，再拼 userId 既冗余、又多一处可能对不上的地方。 */
        fun merchantOrderId(rechargeId: Long): String = "$PREFIX$rechargeId"

        /** `gateway:quota:{id}` → id；不匹配返回 null。 */
        fun parse(merchantOrderId: String): Long? =
            merchantOrderId.takeIf { it.startsWith(PREFIX) }
                ?.removePrefix(PREFIX)
                ?.toLongOrNull()

        /**
         * 归属门：这张单是 [userId] 的才可见，否则**当作不存在**（null），而不是「存在但你无权」。
         *
         * 两者返回同形是刻意的：rechargeId 是自增的，回 403 就等于给探测者装了一台
         * 「哪些 id 存在」的预言机 —— 而存在性本身在这里没有价值，能读的只有自己的单。
         * 与 MyTokenController.requireOwned 同一条规则（那里也写着「不泄露他人令牌是否存在」），
         * 区别只在状态码：读端点回 404 才是准确的语义，400 意思是「你的请求格式不对」，
         * 而请求没毛病、只是这张单对他不可见。
         *
         * 抽成纯函数是为了能被单测覆盖，但要说清覆盖到哪一步：单测钉得住这条规则本身，
         * 钉不住「有没有人真的调它」。后者只有 harness S53 的块 C（另一个真会员读这张单必须
         * 404）与块 D（那个 404 必须与「查无此单」逐字同形）能钉。实测：把 [findForUser] 里那次
         * visibleTo 调用拿掉，单测 170/0 全绿，S53 变成 92/2 —— 与 QuotaLogicTest 里 balanceAfter*
         * 那几条的诚实标注同一处理方式。
         */
        fun visibleTo(recharge: GatewayQuotaRecharge?, userId: Long): GatewayQuotaRecharge? =
            recharge?.takeIf { it.userId == userId }

        private const val RECHARGE_SUBJECT = "NetonAPI 网关额度充值"
    }
}
