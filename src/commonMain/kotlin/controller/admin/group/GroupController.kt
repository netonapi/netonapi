package controller.admin.group

import kotlinx.serialization.Serializable
import model.Channel
import model.GatewayGroup
import model.GatewayToken
import neton.core.annotations.*
import neton.database.dsl.*
import table.ChannelTable
import table.GatewayGroupTable
import table.GatewayTokenTable

@Serializable
data class SaveGroupRequest(
    val id: Long = 0,
    /** 稳定身份，创建后不可改；路由、Key 覆盖、计价都按它匹配 */
    val code: String = "",
    /** 显示名，可随时改 */
    val name: String,
    val ratio: String = "1.0",
    val description: String? = null,
    /** 映射到 module-member 的会员组；null = 不参与会员组映射 */
    val memberGroupId: Long? = null,
)

@Controller("/gateway/group")
class GroupController {

    @Get("/list")
    @Permission("gateway:group:list")
    suspend fun list(): List<GatewayGroup> = GatewayGroupTable.findAll()

    @Get("/get/{id}")
    @Permission("gateway:group:get")
    suspend fun get(@PathVariable id: Long): GatewayGroup? = GatewayGroupTable.get(id)

    @Post("/create")
    @Permission("gateway:group:create")
    suspend fun create(@Body request: SaveGroupRequest): Long {
        val code = requireCode(request.code)
        // 插入前先查重，不能只靠唯一索引兜底：索引冲突抛的是数据库异常，到调用方
        // 那儿是一句看不懂的 500。索引仍然保留 —— 并发下只有它挡得住。
        // 分组是配置表（几十行量级），findAll 的代价可以忽略。
        if (GatewayGroupTable.findAll().any { it.code == code }) {
            throw IllegalArgumentException("gateway group code='$code' already exists")
        }
        requireMappingFree(request.memberGroupId, exceptId = null)
        return GatewayGroupTable.insert(
            GatewayGroup(
                code = code,
                name = request.name,
                ratio = logic.PricingCalc.requireRatio(request.ratio),
                description = request.description,
                memberGroupId = request.memberGroupId,
            )
        ).id
    }

    @Put("/update")
    @Permission("gateway:group:update")
    suspend fun update(@Body request: SaveGroupRequest) {
        val current = GatewayGroupTable.get(request.id)
            ?: throw IllegalArgumentException("gateway group id=${request.id} not found")
        // code 不可变。带了不同的 code 就**拒绝**，而不是静默忽略：静默忽略会让调用方
        // 以为改成功了，而路由与计价仍按旧 code 走 —— 又是一类不报错的错账。
        if (request.code.isNotBlank() && request.code != current.code) {
            throw IllegalArgumentException(
                "gateway group code is immutable (id=${request.id}, code='${current.code}'); " +
                    "create a new group and migrate the references instead",
            )
        }
        requireMappingFree(request.memberGroupId, exceptId = current.id)
        GatewayGroupTable.update(
            current.copy(
                name = request.name,
                ratio = logic.PricingCalc.requireRatio(request.ratio),
                description = request.description,
                memberGroupId = request.memberGroupId,
            )
        )
    }

    @Delete("/delete/{id}")
    @Permission("gateway:group:delete")
    suspend fun delete(@PathVariable id: Long) {
        val current = GatewayGroupTable.get(id)
            ?: throw IllegalArgumentException("gateway group id=$id not found")
        // 被引用的组不许删。软删之后路由静默失配、倍率静默回落，两类都不会自己报错。
        val codeRef: String? = current.code
        if (GatewayTokenTable.existsWhere { GatewayToken::groupOverride eq codeRef }) {
            throw IllegalArgumentException(
                "gateway group code='${current.code}' is still used as a token group override; " +
                    "move those tokens to another group first",
            )
        }
        // channel.groups 是 CSV，用 LIKE 会把 'vip' 匹配到 'vip2'。这里取回来按与
        // RelayRouter.candidates **完全相同**的语义（split + trim + contains）判断，
        // 保证「这里说没被引用」和「路由真的用不到它」是同一件事。渠道是几十行量级。
        val channelRefs = ChannelTable.findAll().count { ch: Channel ->
            ch.groups.split(',').map { it.trim() }.contains(current.code)
        }
        if (channelRefs > 0) {
            throw IllegalArgumentException(
                "gateway group code='${current.code}' is still listed by $channelRefs channel(s); " +
                    "remove it from their groups first",
            )
        }
        GatewayGroupTable.destroy(id)
    }

    /** 一个会员组最多映射到一个计费组；DB 有唯一索引，这里给出可读的失败原因。 */
    private suspend fun requireMappingFree(memberGroupId: Long?, exceptId: Long?) {
        if (memberGroupId == null) return
        val existing = GatewayGroupTable.findAll().firstOrNull { it.memberGroupId == memberGroupId }
        if (existing != null && existing.id != exceptId) {
            throw IllegalArgumentException(
                "member group $memberGroupId is already mapped to gateway group '${existing.code}'",
            )
        }
    }

    /**
     * code 只允许字母/数字/`_`/`-`：它会出现在 channel.groups 的 CSV 里，逗号必须排除；
     * 它也会进日志与错误消息，空白与引号同样排除。
     */
    private fun requireCode(raw: String): String {
        val code = raw.trim()
        require(code.isNotEmpty()) { "gateway group code must not be blank" }
        require(code.length <= 64) { "gateway group code must be at most 64 characters" }
        require(code.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            "gateway group code must contain only letters, digits, '_' and '-'"
        }
        return code
    }
}
