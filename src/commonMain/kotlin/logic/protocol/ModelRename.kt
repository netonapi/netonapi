package logic.protocol

import kotlinx.serialization.json.JsonPrimitive

/**
 * 把上游响应里的 `model` 改回客户端请求时用的名字 —— 渠道 modelMapping 的反向。
 *
 * 映射只在请求方向做过（RequestRewriter 把 gpt-4o 改写成渠道那边的 deployment / 别名），
 * 响应原样透传的话客户端会看到一个它从没请求过的模型名：SDK 按 model 做缓存键、做成本统计、
 * 做日志归类的都会乱，而且泄露了平台后面接的是谁。
 *
 * 用正则而不是整体 JSON 解析：非流式 body 和每个 SSE 块都要过一遍，解析再序列化会改动
 * 空白、字段顺序、数字格式（透传保真是同协议路径的承诺）。模式要求 `"model"` 的引号紧贴
 * 单词，所以出现在 content 文本里的同名串（被转义成 `\"model\"`）不会误中。
 */
object ModelRename {
    fun json(body: String, upstreamModel: String, requestModel: String): String {
        if (upstreamModel == requestModel || body.isEmpty()) return body
        val replacement = "\"model\":" + JsonPrimitive(requestModel).toString()
        return pattern(upstreamModel).replace(body) { replacement }
    }

    /**
     * 流式阶段：按行缓冲，只对已经完整到达的行做替换。SSE 块的切分点是任意的，
     * `"model":"fake-up"` 可能被拆到两个块里，不缓冲就会漏改。
     */
    fun stage(upstreamModel: String, requestModel: String): StreamStage {
        val buf = StringBuilder()
        return StreamStage(
            feed = { chunk ->
                buf.append(chunk)
                val cut = buf.lastIndexOf('\n')
                if (cut < 0) "" else {
                    val complete = buf.substring(0, cut + 1)
                    buf.deleteRange(0, cut + 1)
                    json(complete, upstreamModel, requestModel)
                }
            },
            finish = {
                val rest = buf.toString(); buf.clear()
                json(rest, upstreamModel, requestModel)
            },
        )
    }

    // 正则按上游模型名缓存：流式时每个 SSE 块都要过一遍，逐块编译正则的开销比替换本身还大。
    // 渠道映射目标名的种类是运营配置决定的小集合，上限 256 只是防御性的。
    private val patterns = HashMap<String, Regex>()
    private fun pattern(upstreamModel: String): Regex = patterns[upstreamModel] ?: run {
        if (patterns.size >= 256) patterns.clear()
        Regex("\"model\"\\s*:\\s*\"" + Regex.escape(upstreamModel) + "\"").also { patterns[upstreamModel] = it }
    }
}
