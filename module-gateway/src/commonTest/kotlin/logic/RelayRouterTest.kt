package logic

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayRouterTest {
    private fun ch(id: Long, models: String = "gpt-test", groups: String = "default",
                   priority: Int = 0, weight: Int = 1, status: Int = 1, mapping: String? = null,
                   capabilities: String = "chat") =
        model.Channel(id = id, name = "c$id", type = "openai_compatible", baseUrl = "http://u",
            groups = groups, models = models, modelMapping = mapping, capabilities = capabilities,
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
    fun candidatesFilterByEndpointCapability() {
        val chans = listOf(
            ch(1, capabilities = "chat"),
            ch(2, capabilities = "chat,embeddings"),
            ch(3, models = "other-model", capabilities = "chat,embeddings"),
        )
        assertEquals(listOf(1L, 2L),
            RelayRouter.candidates(chans, "gpt-test", "default", 0, RelayRouter.CAP_CHAT).map { it.id })
        // 只会 chat 的渠道即使命中模型也不进 embeddings 候选（否则上游 404 + 白预留一次钱）
        assertEquals(listOf(2L),
            RelayRouter.candidates(chans, "gpt-test", "default", 0, RelayRouter.CAP_EMBEDDINGS).map { it.id })
    }

    @Test
    fun capabilityForMapsInboundEndpoint() {
        assertEquals(RelayRouter.CAP_EMBEDDINGS, RelayRouter.capabilityFor("/v1/embeddings"))
        assertEquals(RelayRouter.CAP_EMBEDDINGS, RelayRouter.capabilityFor("/v1beta/models/gemini-2.0-flash:embedContent"))
        assertEquals(RelayRouter.CAP_EMBEDDINGS, RelayRouter.capabilityFor("/v1beta/models/gemini-2.0-flash:batchEmbedContents"))
        assertEquals(RelayRouter.CAP_CHAT, RelayRouter.capabilityFor("/v1beta/models/gemini-2.0-flash:streamGenerateContent"))
        assertEquals(RelayRouter.CAP_CHAT, RelayRouter.capabilityFor("/v1/chat/completions"))
        assertEquals(RelayRouter.CAP_CHAT, RelayRouter.capabilityFor("/v1/messages"))
    }

    @Test
    fun noCandidateReasonPinpointsTheBlockingStage() {
        val ok = ch(1)
        fun reason(chans: List<model.Channel>, model: String = "gpt-test", group: String = "default",
                   cap: String = RelayRouter.CAP_CHAT) =
            RelayRouter.noCandidateReason(chans, model, group, 0, cap)

        assertEquals(RelayRouter.Reason.NO_CHANNEL_ENABLED, reason(listOf(ch(9, status = 0))))
        assertEquals(RelayRouter.Reason.COOLDOWN, reason(listOf(ok.copy(cooldownUntil = 10))))
        assertEquals(RelayRouter.Reason.MODEL_NOT_LISTED, reason(listOf(ok), model = "nope"))
        assertEquals(RelayRouter.Reason.GROUP_NOT_SERVED, reason(listOf(ok), group = "vip"))
        assertEquals(RelayRouter.Reason.CAPABILITY_MISSING, reason(listOf(ok), cap = RelayRouter.CAP_EMBEDDINGS))
        // 有候选时本不该被调用；真调了也只能给 UNKNOWN，不能瞎猜原因误导运维
        assertEquals(RelayRouter.Reason.UNKNOWN, reason(listOf(ok)))
        // 永久性原因不给重试希望，暂时性原因才让客户端退避重试
        assertTrue(!RelayRouter.Reason.CAPABILITY_MISSING.retryable)
        assertTrue(!RelayRouter.Reason.MODEL_NOT_LISTED.retryable)
        assertTrue(RelayRouter.Reason.COOLDOWN.retryable)
    }

    @Test
    fun requireCapabilitiesNormalizesAndRejectsUnknown() {
        assertEquals("embeddings,chat", RelayRouter.requireCapabilities(" embeddings , chat ,chat "))
        assertEquals("chat", RelayRouter.requireCapabilities("chat"))
        // 拼错的能力名若被静默接受，该渠道就从对应端点上消失了，只能靠用户投诉发现
        val unknown = assertFailsWith<neton.core.http.ValidationException> { RelayRouter.requireCapabilities("chat,embedding") }
        assertEquals(GatewayErrorCodes.CHANNEL_CAPABILITY_UNKNOWN, unknown.message)
        assertEquals(GatewayErrorCodes.CHANNEL_CAPABILITY_UNKNOWN, unknown.errors.single().code)
        val empty = assertFailsWith<neton.core.http.ValidationException> { RelayRouter.requireCapabilities("  ") }
        assertEquals(GatewayErrorCodes.CHANNEL_CAPABILITIES_REQUIRED, empty.message)
    }

    @Test
    fun pickExcludesTriedAndFallsThrough() {
        val chans = listOf(ch(1, priority = 10), ch(2, priority = 0))
        assertEquals(2L, RelayRouter.pick(chans, excludeIds = setOf(1L), Random(1))?.id)
        assertNull(RelayRouter.pick(chans, excludeIds = setOf(1L, 2L), Random(1)))
    }
}
