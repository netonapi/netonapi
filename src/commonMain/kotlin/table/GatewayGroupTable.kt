package table

import model.GatewayGroup
import model.GatewayGroupTableImpl
import neton.database.api.Table

object GatewayGroupTable : Table<GatewayGroup, Long> by GatewayGroupTableImpl
