package table

import model.PriceRule
import model.PriceRuleTableImpl
import neton.database.api.Table

object PriceRuleTable : Table<PriceRule, Long> by PriceRuleTableImpl
