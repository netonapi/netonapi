package logic.protocol.vertex

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/**
 * Vertex AI 鉴权：渠道 Key 要么是服务账号 JSON（以 `{` 开头，含 client_email / private_key / token_uri），
 * 要么直接是一枚 access token（调试用，过期自己换）。服务账号走 JWT bearer 换 OAuth2 access token，
 * 按 client_email 缓存到过期前 60 秒。
 */
object VertexAuth {
    private data class Cached(val token: String, val expiresAtMs: Long)
    private val cache = HashMap<String, Cached>()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun bearer(apiKey: String, nowMs: Long = kotlin.time.Clock.System.now().toEpochMilliseconds()): String {
        val trimmed = apiKey.trim()
        if (!trimmed.startsWith("{")) return trimmed
        val sa = json.parseToJsonElement(trimmed).jsonObject
        val email = sa["client_email"]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("服务账号 JSON 缺 client_email")
        val hit = mutex.withLock { cache[email]?.takeIf { it.expiresAtMs - 60_000 > nowMs } }
        if (hit != null) return hit.token
        val token = exchange(sa, email, nowMs)
        mutex.withLock { cache[email] = token }
        return token.token
    }

    private suspend fun exchange(sa: JsonObject, email: String, nowMs: Long): Cached {
        val tokenUri = sa["token_uri"]?.jsonPrimitive?.contentOrNull ?: neton.ai.protocol.transport.google.GoogleAccessTokens.DEFAULT_TOKEN_URI
        val assertion = neton.ai.protocol.transport.google.GoogleAccessTokens.assertion(sa, email, tokenUri, nowMs)
        val provider = logic.RelayEngine.clientProvider ?: error("HttpClientProvider not bound")
        val client = provider.create { connectMillis = 5_000; requestMillis = 15_000; socketMillis = 15_000 }
        try {
            val res = client.request(neton.http.client.HttpClientRequest(
                method = neton.http.client.HttpClientMethod.Post, url = tokenUri,
                headers = neton.core.http.HttpHeaders.from(mapOf("Content-Type" to "application/x-www-form-urlencoded")),
                body = neton.http.client.HttpClientBody.Text("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=$assertion", "application/x-www-form-urlencoded"),
            ))
            if (res.statusCode !in 200..299) throw IllegalStateException("Vertex token exchange HTTP ${res.statusCode}: ${res.body.take(300)}")
            val obj = json.parseToJsonElement(res.body).jsonObject
            val token = obj["access_token"]?.jsonPrimitive?.contentOrNull ?: throw IllegalStateException("Vertex token exchange: no access_token")
            val ttl = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
            return Cached(token, nowMs + ttl * 1000)
        } finally {
            runCatching { client.close() }
        }
    }

    /** 厂商参数 JSON 里的 project / location；location 缺省从 Base URL 主机名推（us-central1-aiplatform.googleapis.com）。 */
    fun projectAndLocation(channel: model.Channel): Pair<String, String> {
        val cfg = channel.providerConfig?.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val project = cfg?.get("project")?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("Vertex 渠道的厂商参数 JSON 需要 {\"project\":\"...\"}")
        val host = channel.baseUrl.substringAfter("://").substringBefore('/')
        val location = cfg["location"]?.jsonPrimitive?.contentOrNull
            ?: Regex("^([a-z0-9-]+)-aiplatform\\.googleapis\\.com$").find(host)?.groupValues?.get(1)
            ?: "us-central1"
        return project to location
    }
}
