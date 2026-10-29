package table

import model.ChannelKey
import model.ChannelKeyTableImpl
import neton.database.api.Table

object ChannelKeyTable : Table<ChannelKey, Long> by ChannelKeyTableImpl
