package table

import model.ChannelModelCost
import model.ChannelModelCostTableImpl
import neton.database.api.Table

object ChannelModelCostTable : Table<ChannelModelCost, Long> by ChannelModelCostTableImpl
