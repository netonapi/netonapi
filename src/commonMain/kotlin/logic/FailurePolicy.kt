package logic

import model.ChannelKey

/** 渠道/Key 容错纯策略：失败计数、自动禁用、渠道挂起判定。 */
object FailurePolicy {

    const val DEFAULT_KEY_FAIL_THRESHOLD = 5

    /** 连续失败达阈值 → 自动禁用该 Key（status=2）。 */
    fun shouldDisableKey(failCount: Int, threshold: Int = DEFAULT_KEY_FAIL_THRESHOLD): Boolean =
        failCount >= threshold

    /** 渠道无任何 status=1 的 Key → 挂起（status=2）。 */
    fun shouldSuspendChannel(keys: List<ChannelKey>): Boolean =
        keys.none { it.status == 1 }

    /** 成功清零，失败自增。 */
    fun nextFailCount(current: Int, success: Boolean): Int =
        if (success) 0 else current + 1
}
