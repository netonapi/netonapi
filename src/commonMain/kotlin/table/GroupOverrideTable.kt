package table

import model.GroupOverride
import model.GroupOverrideTableImpl
import neton.database.api.Table

object GroupOverrideTable : Table<GroupOverride, Long> by GroupOverrideTableImpl
