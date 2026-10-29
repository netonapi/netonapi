package table

import model.PriceSyncRun
import model.PriceSyncRunTableImpl
import neton.database.api.Table

object PriceSyncRunTable : Table<PriceSyncRun, Long> by PriceSyncRunTableImpl
