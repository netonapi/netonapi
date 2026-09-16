package logic

import kotlinx.serialization.Serializable
import logic.protocol.ProtocolCodecs
import model.Channel
import neton.core.http.HttpHeaders
import neton.http.client.*
import neton.logging.Logger

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
}
