package logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import model.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 模型 → 服务上游：勾选写回渠道的服务清单（models）与模型映射（model_mapping）。 */
class ModelRoutesTest {

    private fun ch(id: Long, models: String = "", mapping: String? = null, supported: String = "") =
        Channel(id = id, name = "c$id", type = "openai_compatible", baseUrl = "https://u$id.example.com", models = models, modelMapping = mapping, supportedModels = supported)

    private fun mapOf(json: String?) = json?.let { Json.parseToJsonElement(it).jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content } }

    @Test fun routeReportsSupportServingAndUpstreamName() {
        val c = ch(1, models = "gpt-4o,opus", mapping = """{"opus":"claude-opus-4-8"}""", supported = "gpt-4o,claude-opus-4-8")
        val r = ModelRoutes.routeOf(c, "opus")
        assertTrue(r.serving)
        assertEquals("claude-opus-4-8", r.upstreamModel)
        assertTrue(r.supported, "映射到的上游模型在支持列表里也算支持")
        assertFalse(ModelRoutes.routeOf(ch(2, supported = "x"), "opus").supported)
    }

    @Test fun selectingAChannelAddsTheModelAndItsMapping() {
        val out = ModelRoutes.apply(listOf(ch(1, models = "gpt-4o")), "opus", mapOf(1L to "claude-opus-4-8")).single()
        assertEquals("gpt-4o,opus", out.models)
        assertEquals(kotlin.collections.mapOf("opus" to "claude-opus-4-8"), mapOf(out.modelMapping))
    }

    @Test fun sameUpstreamNameNeedsNoMapping() {
        val out = ModelRoutes.apply(listOf(ch(1)), "gpt-4o", mapOf(1L to "gpt-4o")).single()
        assertEquals("gpt-4o", out.models)
        assertNull(out.modelMapping)
    }

    @Test fun deselectingAChannelRemovesModelAndMappingOnly() {
        val c = ch(1, models = "gpt-4o,opus", mapping = """{"opus":"claude-opus-4-8","gpt-4o":"gpt-4o-2024"}""")
        val out = ModelRoutes.apply(listOf(c), "opus", emptyMap()).single()
        assertEquals("gpt-4o", out.models)
        assertEquals(kotlin.collections.mapOf("gpt-4o" to "gpt-4o-2024"), mapOf(out.modelMapping))
    }

    @Test fun untouchedChannelsAreNotReturned() {
        val keep = ch(2, models = "gpt-4o")
        assertTrue(ModelRoutes.apply(listOf(keep), "opus", emptyMap()).isEmpty())
        assertTrue(ModelRoutes.apply(listOf(ch(3, models = "opus")), "opus", mapOf(3L to null)).isEmpty())
    }

    @Test fun changingTheUpstreamNameUpdatesOnlyTheMapping() {
        val c = ch(1, models = "opus", mapping = """{"opus":"old"}""")
        val out = ModelRoutes.apply(listOf(c), "opus", mapOf(1L to "new")).single()
        assertEquals("opus", out.models)
        assertEquals(kotlin.collections.mapOf("opus" to "new"), mapOf(out.modelMapping))
    }
}
