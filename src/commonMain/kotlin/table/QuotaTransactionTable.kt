package table

import model.QuotaTransaction
import model.QuotaTransactionTableImpl
import neton.database.api.Table

object QuotaTransactionTable : Table<QuotaTransaction, Long> by QuotaTransactionTableImpl
