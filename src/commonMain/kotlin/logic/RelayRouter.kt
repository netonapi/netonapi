package logic

import model.Channel
import kotlin.random.Random

/** 渠道路由纯函数：候选过滤 + priority 分层 + 层内 weight 加权随机 + 重试排除。 */
object RelayRouter {

    /** status=1 ∩ 未在 429 冷却期 ∩ 模型命中（models 清单或 modelMapping 键）∩ 分组交集。nowMs 必传，避免漏传致冷却过滤失效。 */
    fun candidates(channels: List<Channel>, model: String, group: String, nowMs: Long): List<Channel> =
        channels.filter { ch ->
            ch.status == 1 &&
                ch.cooldownUntil <= nowMs &&
                modelSupported(ch, model) &&
                ch.groups.split(',').map { it.trim() }.contains(group)
        }

    private fun modelSupported(ch: Channel, model: String): Boolean {
        if (ch.models.split(',').map { it.trim() }.contains(model)) return true
        // 映射键命中：{"请求名":"上游名"}——不做完整 JSON 解析，键名精确匹配即可
        val mapping = ch.modelMapping ?: return false
        return mapping.contains("\"$model\"")
    }

    fun pick(candidates: List<Channel>, excludeIds: Set<Long>, random: Random): Channel? {
        val pool = candidates.filter { it.id !in excludeIds }
        if (pool.isEmpty()) return null
        val topPriority = pool.maxOf { it.priority }
        val layer = pool.filter { it.priority == topPriority }
        val totalWeight = layer.sumOf { it.weight.coerceAtLeast(1) }
        var roll = random.nextInt(totalWeight)
        for (ch in layer) {
            roll -= ch.weight.coerceAtLeast(1)
            if (roll < 0) return ch
        }
        return layer.last()
    }
}
