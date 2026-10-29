package table

import model.Channel
import model.ChannelTableImpl
import neton.database.api.Table

object ChannelTable : Table<Channel, Long> by ChannelTableImpl
