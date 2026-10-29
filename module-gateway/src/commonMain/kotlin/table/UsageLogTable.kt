package table

import model.UsageLog
import model.UsageLogTableImpl
import neton.database.api.Table

object UsageLogTable : Table<UsageLog, Long> by UsageLogTableImpl
