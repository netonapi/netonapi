package table

import model.PriceHistory
import model.PriceHistoryTableImpl
import neton.database.api.Table

object PriceHistoryTable : Table<PriceHistory, Long> by PriceHistoryTableImpl
