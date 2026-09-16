package controller.admin.channel.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateChannelRequest(
    val name: String,
    val type: String,
    val baseUrl: String,
    val groups: String = "default",
    val models: String = "",
    /** 端点能力（CSV，chat/embeddings）：不声明的能力不会收到对应端点的请求 */
    val capabilities: String = "chat",
    val modelMapping: String? = null,
    val paramOverride: String? = null,
    val priority: Int = 0,
    val weight: Int = 1,
    val proxyUrl: String? = null,
    val apiVersion: String? = null,
    val costDiscount: String = "1.0",
    val remark: String? = null,
    /** 初始 Key 列表（可批量粘贴） */
    val keys: List<String> = emptyList(),
)

@Serializable
data class UpdateChannelRequest(
    val id: Long,
    val name: String,
    val type: String,
    val baseUrl: String,
    val groups: String,
    val models: String,
    /** 端点能力（CSV，chat/embeddings）：不声明的能力不会收到对应端点的请求 */
    val capabilities: String = "chat",
    val modelMapping: String? = null,
    val paramOverride: String? = null,
    val priority: Int = 0,
    val weight: Int = 1,
    val proxyUrl: String? = null,
    val apiVersion: String? = null,
    val costDiscount: String = "1.0",
    val remark: String? = null,
)

@Serializable
data class ChannelKeyVO(
    val id: Long,
    /** 前 7 + **** + 后 4，全文不出接口 */
    val display: String,
    val status: Int,
    val failCount: Int,
    val lastError: String? = null,
)

@Serializable
data class AddKeyRequest(val apiKey: String)

@Serializable
data class ProbeChannelRequest(val model: String? = null)
