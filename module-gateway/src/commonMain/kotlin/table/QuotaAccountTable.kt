package table

import model.QuotaAccount
import model.QuotaAccountTableImpl
import neton.database.api.Table

object QuotaAccountTable : Table<QuotaAccount, Long> by QuotaAccountTableImpl
