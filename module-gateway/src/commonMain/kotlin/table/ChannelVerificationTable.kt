package table

import model.ChannelVerification
import model.ChannelVerificationTableImpl
import neton.database.api.Table

object ChannelVerificationTable : Table<ChannelVerification, Long> by ChannelVerificationTableImpl
