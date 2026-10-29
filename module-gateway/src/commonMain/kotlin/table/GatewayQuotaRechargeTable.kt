package table

import model.GatewayQuotaRecharge
import model.GatewayQuotaRechargeTableImpl
import neton.database.api.Table

object GatewayQuotaRechargeTable : Table<GatewayQuotaRecharge, Long> by GatewayQuotaRechargeTableImpl
