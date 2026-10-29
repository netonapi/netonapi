package logic

import kotlinx.serialization.Serializable

/**
 * 下单 SPI：gateway 不认识 payment。
 *
 * 「用哪个支付模块、怎么建单」是产品装配决策，所以由装配层实现并 bind（与 [MemberGroupPort] 同理）。
 * 这样 gateway 编译期对 payment 零依赖，payment 也不用沾网关额度语义。
 *
 * ⚠️ 实现方必须用**调用方给的** [merchantOrderId] 建单，不能自己另编一个：
 * 支付成功事件回来时，gateway 只凭这个号认单（前缀 `gateway:quota:`）。
 */
interface RechargeOrderPort {
    suspend fun createOrder(
        merchantOrderId: String,
        subject: String,
        price: Long,
        channelCode: String?,
        userIp: String?,
    ): RechargePrepay
}

/**
 * 中性的下单结果。gateway 不解释 [payload]（渠道 SDK 参数 / 跳转地址 / 二维码内容），
 * 原样透传给客户端 —— 否则就得 import payment 的 PrepayVO 与 PayDisplayMode，
 * 那条编译依赖正是这个 port 要避免的。
 */
@Serializable
data class RechargePrepay(
    val orderId: Long,
    /** 呈现方式的名字（跳转 / 二维码 / SDK 参数），前端据此决定怎么送用户去付款。 */
    val displayMode: String,
    val payload: String,
    val channelOrderNo: String,
)
