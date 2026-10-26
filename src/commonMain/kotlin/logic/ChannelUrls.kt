package logic

import model.Channel

/** 渠道代理地址：去掉首尾空白后为空就是「不走代理」（管理台表单留空提交的是 ""）。 */
fun effectiveProxyUrl(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

fun Channel.effectiveProxyUrl(): String? = effectiveProxyUrl(proxyUrl)

/**
 * 拼上游地址用的根：上游路径本身带版本段（/v1/chat/completions、/v1/messages、/v1beta/models/…），
 * 运营填的 Base URL 又常常也带（照 OpenAI SDK 的 base_url 写），这里去掉末尾那一段，两种写法都对。
 * Azure、Bedrock、Vertex 的地址规则各不相同，原样使用。
 */
fun Channel.apiBase(): String {
    val b = baseUrl.trim().trimEnd('/')
    return when (type) {
        "openai_compatible", "anthropic" -> b.removeSuffix("/v1")
        "gemini" -> b.removeSuffix("/v1beta").removeSuffix("/v1")
        else -> b
    }
}
