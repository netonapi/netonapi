package logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logic.protocol.ProtocolCodecs
import model.Channel
import neton.core.http.HttpHeaders
import neton.http.client.*
import neton.logging.Logger

@Serializable
data class ChannelModelsResult(
    val ok: Boolean,
    val status: Int? = null,
    val models: List<String> = emptyList(),
    val error: String? = null,
)

@Serializable
data class ChannelProbeResult(
    val ok: Boolean,
    /** 上游 HTTP 状态；网络层失败为 null */
    val status: Int? = null,
    val latencyMs: Long,
    val model: String,
    val upstreamModel: String,
    val upstreamPath: String,
    /** 失败时的上游原话（截断），成功为 null */
    val error: String? = null,
)

/**
 * 渠道连通性探测：用渠道自己的协议、代理、Key 打一条最小的非流式请求，把上游怎么回的原样带回来。
 *
 * 运营配完一条渠道，此前唯一的验证方式是拿一个用户令牌真发请求再去日志里翻 —— 配错的
 * Key / 打错的 base_url / Azure 少了 api-version 全要等第一个客户撞上才知道。探测不进计费、
 * 不写 usage_log、不动 Key 的失败计数（那是给真实流量的容错策略用的，一次手动点测不该把 Key 禁掉）。
 *
 * 请求形状与 [RelayEngine.buildTransform] 的跨协议分支同源：hub（OpenAI）格式 → 按上游协议转码，
 * 路径走同一个 [RelayEngine.upstreamPathFor]，所以探测通了就是真流量也通得了的形状。
 */
@neton.core.annotations.Logic(logger = "logic.gateway-channel-probe")
class ChannelProbeLogic(private val log: Logger, private val channelLogic: ChannelLogic) {

    /** 探测并把结果记到渠道行上（手动探测与定时任务共用）。 */
    suspend fun probeAndRecord(channel: Channel, requestedModel: String? = null): ChannelProbeResult {
        val r = runCatching { probe(channel, requestedModel) }.getOrElse { e ->
            ChannelProbeResult(ok = false, status = null, latencyMs = 0, model = requestedModel ?: "", upstreamModel = "", upstreamPath = "", error = (e.message ?: e::class.simpleName ?: "error").take(500))
        }
        channelLogic.get(channel.id)?.let { fresh ->
            channelLogic.update(fresh.copy(
                lastProbeAt = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                lastProbeMs = r.latencyMs, lastProbeOk = if (r.ok) 1 else 0,
                lastProbeError = r.error?.take(512),
            ))
        }
        return r
    }

    /** 定时任务入口：探测全部启用中的渠道。 */
    suspend fun probeAllEnabled(): Int {
        var n = 0
        for (ch in channelLogic.listEnabled()) { probeAndRecord(ch); n++ }
        return n
    }

    suspend fun probe(channel: Channel, requestedModel: String?): ChannelProbeResult {
        val model = requestedModel?.trim()?.takeIf { it.isNotEmpty() }
            ?: channel.models.split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: throw neton.core.http.HttpException(neton.core.http.NetonErrorCode.INVALID_PARAMS, "渠道没有模型清单，请指定要探测的模型")
        val upstream = RelayEngine.adapterForChannel(channel.type)
            ?: throw neton.core.http.HttpException(neton.core.http.NetonErrorCode.INVALID_PARAMS, "未知渠道类型 ${channel.type}")
        val key = channelLogic.pickKey(channel.id)
            ?: throw neton.core.http.HttpException(neton.core.http.NetonErrorCode.INVALID_PARAMS, "渠道没有可用的 Key")

        val hub = """{"model":${kotlinx.serialization.json.JsonPrimitive(model)},"messages":[{"role":"user","content":"ping"}],"max_tokens":1}"""
        val mapped = RequestRewriter.rewrite(hub, channel, injectStreamOptions = false)
        val body = if (upstream.id == "openai") mapped.body else ProtocolCodecs.requestFromHub(upstream.id)(mapped.body)
        val path = RelayEngine.upstreamPathFor(upstream, channel, "/v1/chat/completions", mapped.upstreamModel, stream = false)
        val request = HttpClientRequest(
            method = HttpClientMethod.Post,
            url = channel.baseUrl.trimEnd('/') + path,
            headers = HttpHeaders.from(upstream.upstreamHeaders(key.apiKey)),
            body = HttpClientBody.Json(body),
        )
        val start = kotlin.time.Clock.System.now().toEpochMilliseconds()
        fun elapsed() = kotlin.time.Clock.System.now().toEpochMilliseconds() - start
        return try {
            val res = RelayEngine.clientFor(channel).request(request)
            val ok = res.statusCode in 200..299
            if (!ok) log.warn("channel probe failed channel=${channel.id} status=${res.statusCode}")
            ChannelProbeResult(
                ok = ok, status = res.statusCode, latencyMs = elapsed(), model = model,
                upstreamModel = mapped.upstreamModel, upstreamPath = path,
                error = if (ok) null else res.body.take(500),
            )
        } catch (e: HttpClientException) {
            log.warn("channel probe error channel=${channel.id} err=${e.error}")
            val err = e.error
            ChannelProbeResult(
                ok = false, status = (err as? HttpClientError.Http)?.statusCode, latencyMs = elapsed(), model = model,
                upstreamModel = mapped.upstreamModel, upstreamPath = path,
                error = ((err as? HttpClientError.Http)?.body ?: err.toString()).take(500),
            )
        }
    }

    /**
     * 拉取上游的模型清单，给运营填「模型清单」用：四种类型各有各的列表端点与字段，
     * 这里统一成裸的模型 id 列表。Azure 列的是 deployment 名（那正是映射目标要填的东西）。
     * 拉不到不算错误配置 —— 很多 OpenAI 兼容站根本不实现 /v1/models，此时返回 ok=false 与上游原话。
     */
    suspend fun listModels(channel: Channel): ChannelModelsResult {
        val upstream = RelayEngine.adapterForChannel(channel.type)
            ?: throw neton.core.http.HttpException(neton.core.http.NetonErrorCode.INVALID_PARAMS, "未知渠道类型 ${channel.type}")
        val key = channelLogic.pickKey(channel.id)
            ?: throw neton.core.http.HttpException(neton.core.http.NetonErrorCode.INVALID_PARAMS, "渠道没有可用的 Key")
        val base = channel.baseUrl.trimEnd('/')
        val (url, extract) = when (channel.type) {
            "azure_openai" -> {
                val v = channel.apiVersion?.trim().takeUnless { it.isNullOrEmpty() } ?: "2023-03-15-preview"
                "$base/openai/deployments?api-version=$v" to ::openAiIds
            }
            "gemini" -> "$base/v1beta/models?pageSize=1000" to ::geminiNames
            else -> "$base/v1/models" to ::openAiIds
        }
        val headers = upstream.upstreamHeaders(key.apiKey).filterKeys { !it.equals("Content-Type", ignoreCase = true) }
        val request = HttpClientRequest(method = HttpClientMethod.Get, url = url, headers = HttpHeaders.from(headers))
        return try {
            val res = RelayEngine.clientFor(channel).request(request)
            if (res.statusCode !in 200..299) {
                ChannelModelsResult(ok = false, status = res.statusCode, error = res.body.take(500))
            } else {
                val ids = runCatching { extract(res.body) }.getOrNull()
                if (ids == null) ChannelModelsResult(ok = false, status = res.statusCode, error = "无法解析模型列表：" + res.body.take(200))
                else ChannelModelsResult(ok = true, status = res.statusCode, models = ids.distinct().sorted())
            }
        } catch (e: HttpClientException) {
            val err = e.error
            ChannelModelsResult(ok = false, status = (err as? HttpClientError.Http)?.statusCode, error = ((err as? HttpClientError.Http)?.body ?: err.toString()).take(500))
        }
    }

    private fun openAiIds(body: String): List<String> =
        kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["data"]!!.jsonArray
            .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }

    private fun geminiNames(body: String): List<String> =
        kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["models"]!!.jsonArray
            .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/") }
}
