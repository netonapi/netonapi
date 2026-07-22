package logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FailurePolicyTest {
    @Test
    fun keyDisabledAtThreshold() {
        assertFalse(FailurePolicy.shouldDisableKey(failCount = 4, threshold = 5))
        assertTrue(FailurePolicy.shouldDisableKey(failCount = 5, threshold = 5))
        assertTrue(FailurePolicy.shouldDisableKey(failCount = 6, threshold = 5))
    }

    @Test
    fun channelSuspendedWhenNoUsableKey() {
        fun key(status: Int) = model.ChannelKey(id = 1, channelId = 1, apiKey = "k", status = status)
        assertTrue(FailurePolicy.shouldSuspendChannel(listOf(key(0), key(2))))   // 全禁用
        assertFalse(FailurePolicy.shouldSuspendChannel(listOf(key(0), key(1))))  // 有一个可用
        assertTrue(FailurePolicy.shouldSuspendChannel(emptyList()))              // 无 Key
    }

    @Test
    fun nextFailCountResetsOnSuccess() {
        assertEquals(0, FailurePolicy.nextFailCount(current = 3, success = true))
        assertEquals(4, FailurePolicy.nextFailCount(current = 3, success = false))
    }
}
