package logic

import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelLogicTest {
    @Test
    fun pickAvailableSkipsDisabledKeys() {
        val keys = listOf(
            model.ChannelKey(id = 1, channelId = 1, apiKey = "sk-a", status = 0),
            model.ChannelKey(id = 2, channelId = 1, apiKey = "sk-b", status = 1),
            model.ChannelKey(id = 3, channelId = 1, apiKey = "sk-c", status = 2),
        )
        assertEquals(2L, ChannelLogic.pickAvailable(keys)?.id)
    }

    @Test
    fun pickAvailableRotatesByLeastRecentlyUsed() {
        val keys = listOf(
            model.ChannelKey(id = 1, channelId = 1, apiKey = "sk-a", status = 1, lastUsedAt = 200),
            model.ChannelKey(id = 2, channelId = 1, apiKey = "sk-b", status = 1, lastUsedAt = 100),
        )
        assertEquals(2L, ChannelLogic.pickAvailable(keys)?.id)
    }

    @Test
    fun pickAvailableReturnsNullWhenAllDisabled() {
        val keys = listOf(model.ChannelKey(id = 1, channelId = 1, apiKey = "sk-a", status = 0))
        assertEquals(null, ChannelLogic.pickAvailable(keys))
    }
}
