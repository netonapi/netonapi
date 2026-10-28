package controller.admin.group

import kotlinx.serialization.Serializable
import model.Channel
import model.GatewayGroup
import model.GroupOverride
import model.GatewayToken
import neton.core.annotations.*
import neton.core.http.HttpException
import neton.core.http.NetonErrorCode
import neton.core.http.ValidationError
import logic.GatewayErrorCodes
import logic.rejectField
import neton.database.dsl.*
import table.ChannelTable
import table.GatewayGroupTable
import table.GroupOverrideTable
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
    /** 映射到 module-member 的会员组：该会员组的用户可以选这个分组；null = 不映射 */
    val memberGroupId: Long? = null,
    /** 1 = 所有用户建 Key 时都能选这个分组 */
    val selectable: Int = 0,
)

@Controller("/gateway/group")
class GroupController {

    @Get("/list")
    @Permission("gateway:group:query")
    suspend fun list(): List<GatewayGroup> = GatewayGroupTable.findAll()

    @Get("/get/{id}")
    @Permission("gateway:group:query")
    suspend fun get(@PathVariable id: Long): GatewayGroup? = GatewayGroupTable.get(id)

    @Post("/create")
    @Permission("gateway:group:create")
    suspend fun create(@Body request: SaveGroupRequest): Long {
        val code = requireCode(request.code)
        // 插入前先查重，为的是给出可读原因；并发下真正挡得住的只有 uk_gateway_groups_code。
        // 分组是配置表（几十行量级），findAll 的代价可以忽略。
        if (GatewayGroupTable.findAll().any { it.code == code }) alreadyExists()
        requireMappingFree(request.memberGroupId, exceptId = null)
        return try {
            GatewayGroupTable.insert(
                GatewayGroup(
                    code = code,
                    name = request.name,
                    ratio = logic.PricingCalc.requireRatio(request.ratio),
                    description = request.description,
                    memberGroupId = request.memberGroupId,
                    selectable = if (request.selectable == 1) 1 else 0,
                )
            ).id
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 协程取消原样上抛，吞掉会让取消语义失效
        } catch (e: Throwable) {
            // 两个并发创建会同时通过上面的查重，输的那个撞唯一索引。这里**不解析驱动的报错文本**
            // （三方言各不相同、还随版本漂），改为回问数据库：此刻已有同 code 的活行 = 被人抢先建了。
            // 问不出活行说明失败另有原因，原样上抛 —— 把真故障盖成 409 比留一个 500 更难查。
            if (GatewayGroupTable.existsWhere { GatewayGroup::code eq code }) alreadyExists()
            throw e
        }
    }

    @Put("/update")
    @Permission("gateway:group:update")
    suspend fun update(@Body request: SaveGroupRequest) {
        val current = GatewayGroupTable.get(request.id)
            ?: throw HttpException(NetonErrorCode.RESOURCE_NOT_FOUND, GatewayErrorCodes.GROUP_NOT_FOUND)
        // code 不可变。带了不同的 code 就**拒绝**，而不是静默忽略：静默忽略会让调用方
        // 以为改成功了，而路由与计价仍按旧 code 走 —— 又是一类不报错的错账。
        if (request.code.isNotBlank() && request.code != current.code) {
            throw HttpException(
                NetonErrorCode.OPERATION_CONFLICT,
                GatewayErrorCodes.GROUP_CODE_IMMUTABLE,
                listOf(ValidationError("code", GatewayErrorCodes.GROUP_CODE_IMMUTABLE, GatewayErrorCodes.GROUP_CODE_IMMUTABLE)),
            )
        }
        requireMappingFree(request.memberGroupId, exceptId = current.id)
        GatewayGroupTable.update(
            current.copy(
                name = request.name,
                ratio = logic.PricingCalc.requireRatio(request.ratio),
                description = request.description,
                memberGroupId = request.memberGroupId,
                selectable = if (request.selectable == 1) 1 else 0,
            )
        )
    }

    @Delete("/delete/{id}")
    @Permission("gateway:group:delete")
    suspend fun delete(@PathVariable id: Long) {
        val current = GatewayGroupTable.get(id)
            ?: throw HttpException(NetonErrorCode.RESOURCE_NOT_FOUND, GatewayErrorCodes.GROUP_NOT_FOUND)
        // 被引用的组不许删。软删之后路由静默失配、倍率静默回落，两类都不会自己报错。
        val codeRef: String? = current.code
        if (GatewayTokenTable.existsWhere { GatewayToken::groupOverride eq codeRef }) {
            throw HttpException(NetonErrorCode.OPERATION_CONFLICT, GatewayErrorCodes.GROUP_IN_USE_BY_TOKEN)
        }
        // 用户级例外同样算引用。漏掉它会留下悬空 group_code，而悬空之后该用户走哪个组
        // 取决于 GroupResolver 的回落策略 —— 也就是一处不报错的错账。
        if (GroupOverrideTable.existsWhere { GroupOverride::groupCode eq current.code }) {
            throw HttpException(NetonErrorCode.OPERATION_CONFLICT, GatewayErrorCodes.GROUP_IN_USE_BY_OVERRIDE)
        }
        // channel.groups 是 CSV，用 LIKE 会把 'vip' 匹配到 'vip2'。这里取回来按与
        // RelayRouter.candidates **完全相同**的语义（split + trim + contains）判断，
        // 保证「这里说没被引用」和「路由真的用不到它」是同一件事。渠道是几十行量级。
        val channelRefs = ChannelTable.findAll().count { ch: Channel ->
            ch.groups.split(',').map { it.trim() }.contains(current.code)
        }
        if (channelRefs > 0) {
            throw HttpException(NetonErrorCode.OPERATION_CONFLICT, GatewayErrorCodes.GROUP_IN_USE_BY_CHANNEL)
        }
        GatewayGroupTable.destroy(id)
    }

    /** 一个会员组最多映射到一个计费组；DB 有唯一索引，这里给出明确的失败原因。 */
    private suspend fun requireMappingFree(memberGroupId: Long?, exceptId: Long?) {
        if (memberGroupId == null) return
        val existing = GatewayGroupTable.findAll().firstOrNull { it.memberGroupId == memberGroupId }
        if (existing != null && existing.id != exceptId) {
            throw HttpException(
                NetonErrorCode.OPERATION_CONFLICT,
                GatewayErrorCodes.MEMBER_GROUP_ALREADY_MAPPED,
                listOf(ValidationError("memberGroupId", GatewayErrorCodes.MEMBER_GROUP_ALREADY_MAPPED, GatewayErrorCodes.MEMBER_GROUP_ALREADY_MAPPED)),
            )
        }
    }

    /**
     * code 只允许字母/数字/`_`/`-`：它会出现在 channel.groups 的 CSV 里，逗号必须排除；
     * 它也会进日志与错误消息，空白与引号同样排除。
     */
    private fun requireCode(raw: String): String {
        val code = raw.trim()
        if (code.isEmpty()) rejectField("code", GatewayErrorCodes.GROUP_CODE_REQUIRED)
        if (code.length > 64) rejectField("code", GatewayErrorCodes.GROUP_CODE_TOO_LONG)
        if (!code.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            rejectField("code", GatewayErrorCodes.GROUP_CODE_INVALID_FORMAT)
        }
        return code
    }

    /** code 撞唯一约束（含并发抢建）→ 409，与插入前的查重同一语义 */
    private fun alreadyExists(): Nothing =
        throw HttpException(
            NetonErrorCode.RESOURCE_ALREADY_EXISTS,
            GatewayErrorCodes.GROUP_CODE_ALREADY_EXISTS,
            listOf(ValidationError("code", GatewayErrorCodes.GROUP_CODE_ALREADY_EXISTS, GatewayErrorCodes.GROUP_CODE_ALREADY_EXISTS)),
        )
}
