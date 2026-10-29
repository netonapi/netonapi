package table

import model.GatewayToken
import model.GatewayTokenTableImpl
import neton.database.api.Table

object GatewayTokenTable : Table<GatewayToken, Long> by GatewayTokenTableImpl
