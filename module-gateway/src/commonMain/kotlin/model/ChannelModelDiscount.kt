package model

import neton.database.annotations.Id
import neton.database.annotations.Table

@Table("gateway_channel_model_discounts")
data class ChannelModelDiscount(
    @Id val id: Long = 0,
    val channelId: Long,
    val model: String,
    val discount: String,
)
