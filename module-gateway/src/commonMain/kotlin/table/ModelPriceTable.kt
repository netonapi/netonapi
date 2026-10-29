package table

import model.ModelPrice
import model.ModelPriceTableImpl
import neton.database.api.Table

object ModelPriceTable : Table<ModelPrice, Long> by ModelPriceTableImpl
