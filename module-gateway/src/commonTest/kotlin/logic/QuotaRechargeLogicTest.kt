package logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import model.GatewayQuotaRecharge

class QuotaRechargeLogicTest {

    /** 一张属于 user 1 的意图单。构造它不碰库 —— GatewayQuotaRecharge 是普通 data class。 */
    private fun order(userId: Long) = GatewayQuotaRecharge(
        id = 7, userId = userId, amountMicro = 100_000, price = 100, payStatus = 0,
    )

    /**
     * 归属门：不是自己的单必须与「不存在」**同形**（都返回 null）。
     *
     * ⚠️ 这条**不是端到端行为守卫**，别把它当成「越权读被单测看着」。它钉的是
     * [QuotaRechargeLogic.visibleTo] 这条规则本身，钉不住「有没有人真的调它」—— 把 findForUser
     * 里的 visibleTo 拿掉，归属门就整个被绕过，而本文件 3 条全绿（模块 170/0）。这不是推测，
     * 是实测过的变异。真正抓住它的是 harness S53 的块 C（另一个真会员读这张单必须 404）与
     * 块 D（那个 404 必须与「查无此单」同形），两者一起红 = 92/2。
     * **所以改归属判断必须跑 S53，跑单测不够。** 与 QuotaLogicTest 里 balanceAfter* 那几条
     * 同一处理方式。反过来，把 visibleTo 本身改成 `recharge`（门拆掉）会让本条立刻红 ——
     * 单测守的是规则，S53 守的是接线，两层各管一头。
     *
     * 留着它的价值是文档一致性：谁把 404 改成 403（返回一个非 null 的哨兵、或让「不是你的」
     * 与「查无此单」两种情况可区分）就会红。为什么必须同形 —— rechargeId 是自增的，
     * 「存在但不是你的」这个信号本身就是一台预言机，而它在这里不提供任何价值：
     * 能读的只有自己的单。
     */
    @Test
    fun anotherUsersOrderIsIndistinguishableFromAMissingOne() {
        val mine = order(userId = 1)
        assertEquals(mine, QuotaRechargeLogic.visibleTo(mine, userId = 1))
        assertNull(QuotaRechargeLogic.visibleTo(mine, userId = 2))
        // 不存在的单子走同一条出口：null 进 null 出，不另造一种「查无此单」的形态
        assertNull(QuotaRechargeLogic.visibleTo(null, userId = 1))
    }

    /**
     * merchantOrderId 的双向约定：造出来的号必须能被 parse 认回同一个 id。
     *
     * 这两个函数是「意图单 ↔ payment 订单」之间唯一的桥，而它有三处调用方：装配层的监听者靠
     * parse 认单，markPaid 靠 merchantOrderId 当台账 ref，回读端点靠 merchantOrderId 报给客服。
     * 三处各算一次，所以规则只能有一份实现（都在 companion 里）。谁在前缀上动手，认单与写 ref
     * 会同时错位，账面上的表现是「付了钱没额度」—— 那是最难查的一类，因为两边各自都「成功」了。
     * 用 Long.MAX_VALUE 是为了顺带钉住 parse 不截断：这一路全程是字符串与 Long，不经 JS number。
     */
    @Test
    fun merchantOrderIdRoundTrips() {
        for (id in listOf(1L, 42L, Long.MAX_VALUE)) {
            val moid = QuotaRechargeLogic.merchantOrderId(id)
            assertEquals("gateway:quota:$id", moid)
            assertEquals(id, QuotaRechargeLogic.parse(moid))
        }
    }

    /**
     * 前缀分流：钱包充值号不能被网关认走。
     *
     * 同一个「支付成功」事件由多个消费方各取所需，靠的就是这个前缀（见 QuotaRechargeLogic 的
     * 类级 KDoc）。parse 若认下 `wallet:recharge:`，一次钱包充值会同时给网关额度入账 ——
     * 同一笔钱发出两份价值。harness S34 从端到端钉这件事，这里钉的是它依赖的那条解析规则：
     * 宁可认不出（回调重投时会再走一次），也不要猜。
     */
    @Test
    fun parseOnlyAcceptsItsOwnPrefix() {
        assertNull(QuotaRechargeLogic.parse("wallet:recharge:7"))
        assertNull(QuotaRechargeLogic.parse("gateway:quota:"))    // 空 id
        assertNull(QuotaRechargeLogic.parse("gateway:quota:abc")) // 不是数
        assertNull(QuotaRechargeLogic.parse("gateway:quota:7 "))  // 尾随空格也不认
        assertEquals(7L, QuotaRechargeLogic.parse("gateway:quota:7"))
    }
    // ---- 退款扣回 ----

    /** 全额退款扣回全部额度；部分退款按「退款金额 / 订单金额」比例扣，向下取整（少扣一点不至于多扣用户的钱）。 */
    @Test
    fun clawbackIsProportionalToTheRefund() {
        assertEquals(10_000_000L, QuotaRechargeLogic.clawbackMicro(amountMicro = 10_000_000, refundPrice = 1000, orderPrice = 1000))
        assertEquals(5_000_000L, QuotaRechargeLogic.clawbackMicro(amountMicro = 10_000_000, refundPrice = 500, orderPrice = 1000))
        assertEquals(3_333_333L, QuotaRechargeLogic.clawbackMicro(amountMicro = 10_000_000, refundPrice = 1, orderPrice = 3))
        // 退款金额超过订单金额（不该发生）按全额封顶，不多扣
        assertEquals(10_000_000L, QuotaRechargeLogic.clawbackMicro(amountMicro = 10_000_000, refundPrice = 2000, orderPrice = 1000))
    }

    /** 大额不溢出：μUSD 额度乘上「分」很容易超过 Long，Kotlin 的溢出是静默回绕的。 */
    @Test
    fun clawbackDoesNotOverflowOnLargeAmounts() {
        val amount = 9_000_000_000_000L          // 900 万美元的 μUSD
        assertEquals(amount / 2, QuotaRechargeLogic.clawbackMicro(amount, refundPrice = 50_000_000, orderPrice = 100_000_000))
    }

    @Test
    fun refundRefUsesItsOwnPrefix() {
        assertEquals("refund:gateway:quota:7", QuotaRechargeLogic.refundRef(7))
    }
}

