package table

import model.Settlement
import model.SettlementTableImpl
import neton.database.api.Table

object SettlementTable : Table<Settlement, Long> by SettlementTableImpl
