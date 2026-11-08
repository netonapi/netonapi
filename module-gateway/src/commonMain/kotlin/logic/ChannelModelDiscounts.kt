package logic

import model.ChannelModelDiscount
import neton.database.dbContext
import neton.database.dsl.*
import table.ChannelModelDiscountTable

object ChannelModelDiscounts {
    suspend fun all(): Map<Pair<Long, String>, String> =
        ChannelModelDiscountTable.findAll().associate { (it.channelId to it.model) to it.discount }

    suspend fun forChannel(channelId: Long): Map<String, String> =
        ChannelModelDiscountTable.query { where { ChannelModelDiscount::channelId eq channelId } }
            .list().associate { it.model to it.discount }

    suspend fun find(channelId: Long, model: String): String? =
        ChannelModelDiscountTable.oneWhere {
            and(ChannelModelDiscount::channelId eq channelId, ChannelModelDiscount::model eq model)
        }?.discount

    suspend fun save(channelId: Long, model: String, discount: String) {
        dbContext().execute(
            "INSERT INTO gateway_channel_model_discounts (channel_id, model, discount) VALUES (:id, :model, :discount) " +
                "ON CONFLICT (channel_id, model) DO UPDATE SET discount = EXCLUDED.discount",
            mapOf("id" to channelId, "model" to model, "discount" to discount),
        )
    }

    suspend fun clear(channelId: Long, model: String) {
        dbContext().execute("DELETE FROM gateway_channel_model_discounts WHERE channel_id = :id AND model = :model",
            mapOf("id" to channelId, "model" to model))
    }

    fun required(): Nothing = rejectField("costDiscount", GatewayErrorCodes.CHANNEL_MODEL_DISCOUNT_REQUIRED)
}
