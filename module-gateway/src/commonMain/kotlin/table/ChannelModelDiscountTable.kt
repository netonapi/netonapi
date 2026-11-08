package table

import model.ChannelModelDiscount
import model.ChannelModelDiscountTableImpl
import neton.database.api.Table

object ChannelModelDiscountTable : Table<ChannelModelDiscount, Long> by ChannelModelDiscountTableImpl
