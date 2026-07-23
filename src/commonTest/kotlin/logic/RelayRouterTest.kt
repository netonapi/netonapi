package logic

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayRouterTest {
    private fun ch(id: Long, models: String = "gpt-test", groups: String = "default",
                   priority: Int = 0, weight: Int = 1, status: Int = 1, mapping: String? = null) =
        model.Channel(id = id, name = "c$id", type = "openai_compatible", baseUrl = "http://u",
            groups = groups, models = models, modelMapping = mapping,
            priority = priority, weight = weight, status = status)

    @Test
    fun candidatesFilterByModelGroupStatus() {
        val chans = listOf(
            ch(1),                                        // 命中
            ch(2, models = "other-model"),                // 模型不符
            ch(3, groups = "vip"),                        // 组不符
            ch(4, status = 0),                            // 禁用
            ch(5, models = "", mapping = """{"gpt-test":"upstream-x"}"""), // 映射键命中
        )
        val got = RelayRouter.candidates(chans, model = "gpt-test", group = "default", nowMs = 0).map { it.id }
        assertEquals(listOf(1L, 5L), got)
    }

    @Test
    fun pickPrefersHighestPriorityLayer() {
        val chans = listOf(ch(1, priority = 0, weight = 100), ch(2, priority = 10, weight = 1))
        repeat(10) {
            assertEquals(2L, RelayRouter.pick(chans, emptySet(), Random(it))?.id)
        }
    }

    @Test
    fun pickWeightedWithinLayer() {
        val chans = listOf(ch(1, weight = 1), ch(2, weight = 9))
        val hits = (0 until 200).count { RelayRouter.pick(chans, emptySet(), Random(it))?.id == 2L }
        assertTrue(hits > 140, "weight=9 应显著多于 weight=1，实际 $hits/200")
    }

    @Test
    fun pickExcludesTriedAndFallsThrough() {
        val chans = listOf(ch(1, priority = 10), ch(2, priority = 0))
        assertEquals(2L, RelayRouter.pick(chans, excludeIds = setOf(1L), Random(1))?.id)
        assertNull(RelayRouter.pick(chans, excludeIds = setOf(1L, 2L), Random(1)))
    }
}
