package logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import model.Channel

/**
 * 模型 → 服务上游（docs/model-catalog-design.md）。路由仍按渠道的服务清单（models）与模型映射（model_mapping）走，
 * 这里只是从「模型」这一侧去读写它们：勾选一个上游 = 把模型加进它的服务清单（上游侧名字不同就再写一条映射）；
 * 取消 = 只拿掉这个模型与它的映射，渠道上别的模型不动。纯函数，写库在 ChannelController。
 */
object ModelRoutes {

    private val json = Json { ignoreUnknownKeys = true }

    /** 一个渠道相对某个模型的状态。 */
    data class Route(val serving: Boolean, val supported: Boolean, val upstreamModel: String?)

    fun routeOf(channel: Channel, model: String): Route {
        val upstream = mappingOf(channel.modelMapping)[model]
        val supported = csv(channel.supportedModels).let { model in it || (upstream != null && upstream in it) }
        return Route(serving = model in csv(channel.models), supported = supported, upstreamModel = upstream)
    }

    /**
     * 让 [model] 恰好由 [selected] 里的渠道服务（channelId → 上游侧模型名，null / 空 / 与模型同名 = 不映射）。
     * 返回**有变化**的渠道副本，没变的不返回（省得无谓地写库、刷新渠道客户端）。
     */
    fun apply(channels: List<Channel>, model: String, selected: Map<Long, String?>): List<Channel> = channels.mapNotNull { ch ->
        val models = csv(ch.models)
        val mapping = mappingOf(ch.modelMapping).toMutableMap()
        val newModels: List<String>
        if (ch.id in selected) {
            newModels = if (model in models) models else models + model
            val up = selected[ch.id]?.trim()?.takeIf { it.isNotEmpty() && it != model }
            if (up == null) mapping.remove(model) else mapping[model] = up
        } else {
            newModels = models - model
            mapping.remove(model)
        }
        val newMapping = if (mapping.isEmpty()) null else JsonObject(mapping.mapValues { JsonPrimitive(it.value) }).toString()
        val changed = newModels != models || mapping != mappingOf(ch.modelMapping)
        if (changed) ch.copy(models = newModels.joinToString(","), modelMapping = newMapping) else null
    }

    private fun csv(s: String): List<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun mappingOf(raw: String?): Map<String, String> = raw?.takeIf { it.isNotBlank() }?.let {
        runCatching { json.parseToJsonElement(it).jsonObject.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { c -> k to c } }.toMap() }.getOrNull()
    }.orEmpty()
}
