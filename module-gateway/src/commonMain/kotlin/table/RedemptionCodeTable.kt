package table

import model.RedemptionCode
import model.RedemptionCodeTableImpl
import neton.database.api.Table

object RedemptionCodeTable : Table<RedemptionCode, Long> by RedemptionCodeTableImpl
