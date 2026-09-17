package controller.admin.group

import kotlinx.serialization.Serializable
import logic.MemberDirectory
import model.GatewayGroup
import model.GroupOverride
import neton.core.annotations.*
import neton.core.http.HttpException
import neton.core.http.NetonErrorCode
import neton.database.dsl.*
import table.GatewayGroupTable
import table.GroupOverrideTable

@Serializable
data class SaveGroupOverrideRequest(
    /** 例外挂在谁身上。它同时是主键：一个用户最多一条例外。 */
    val userId: Long = 0,
    /** 指向 [GatewayGroup.code]（稳定身份，不是可改的显示名）。 */
    val groupCode: String = "",
    /** 运营备注（合同号、审批人、有效期口径等）。DDL 限 256。 */
    val remark: String? = null,
)

/**
 * 用户级计费组例外（大客户谈判价）的管理端 CRUD。
 *
 * 这张表是 [logic.BillingGroupResolver] 解析顺序的第 2 层：
 * 令牌覆盖 → **用户级例外** → 会员组映射 → `default`。在此之前它只有读路径、没有写路径 ——
 * 谈判价这个功能在生产上够不着，只能手工改库。而手工改库正是悬空 `group_code` 的来源，
 * 悬空之后解析器按 [logic.GroupReject.CONFIG_BROKEN] 拒绝（HTTP 500），
 * **对那一个用户的每一个请求都 500**，且不回落。所以补 CRUD 不只是补便利，
 * 它是把「写入时校验组是活的」这条防线补上 —— [GroupController.delete] 已经从另一头
 * 挡了「删掉仍被例外引用的组」，两头合起来才闭环。
 *
 * ⚠️ 这些路由**只能挂在 admin 组**，绝不能出现在 app 侧：自助用户能设自己的计费组，
 * 就等于自己给自己打折。harness 里有一条专门断言 app 组下这些路径是 404。
 *
 * `userId` 的存在性由 [logic.MemberDirectoryPort] 校验（装配层实现并 bind）：写错一位数字
 * 得到的是 400 与一句说清后果的话，而不是「例外静静躺着、客户仍按 `default` 组计价、
 * 双方都不报错」。port 未绑定时退化成只校验正数，[init.GatewayRuntimeBootstrap] 会 warn，
 * 取舍见 [logic.MemberDirectory]。只有 [create] 校验，理由见 [requireChargeableMember]。
 *
 * 权限串按 rbac-spec §3.1 用下划线连接复合词（`group_override`）。动词拆成
 * list / get / create / update / delete，为的是能把「查谈判价」与「改谈判价」分给不同角色 ——
 * 改一个人的计价倍率是直接改收入，比查看敏感得多。
 */
@Controller("/gateway/group-override")
class GroupOverrideController {

    @Get("/list")
    @Permission("gateway:group-override:query")
    suspend fun list(): List<GroupOverride> = GroupOverrideTable.findAll()

    @Get("/get/{userId}")
    @Permission("gateway:group-override:query")
    suspend fun get(@PathVariable userId: Long): GroupOverride? = GroupOverrideTable.get(userId)

    @Post("/create")
    @Permission("gateway:group-override:create")
    suspend fun create(@Body request: SaveGroupOverrideRequest): Long {
        val userId = requireUserId(request.userId)
        requireChargeableMember(userId)
        val remark = normalizeRemark(request.remark)
        val code = requireLiveGroupCode(request.groupCode)
        // 插入前先查重，为的是给出可读原因；并发下真正挡得住的只有主键 user_id。
        if (GroupOverrideTable.get(userId) != null) alreadyExists(userId)
        return try {
            GroupOverrideTable.insert(
                GroupOverride(userId = userId, groupCode = code, remark = remark)
            ).userId
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 协程取消原样上抛，吞掉会让取消语义失效
        } catch (e: Throwable) {
            // 两个并发创建会同时通过上面的查重，输的那个撞主键。与 GroupController.create 同一套：
            // **不解析驱动的报错文本**（三方言各不相同、还随版本漂），改为回问数据库 ——
            // 此刻已有该用户的行 = 被人抢先建了。问不出行说明失败另有原因，原样上抛：
            // 把真故障盖成 409 比留一个 500 更难查。
            if (GroupOverrideTable.existsWhere { GroupOverride::userId eq userId }) alreadyExists(userId)
            throw e
        }
    }

    /**
     * 改例外指向的组或备注。[SaveGroupOverrideRequest.userId] 在这里是**定位键**而不是可改字段：
     * 换用户不是更新，是删掉旧的再建一条 —— 静默改主键会让审计记录对不上原来那个人。
     */
    @Put("/update")
    @Permission("gateway:group-override:update")
    suspend fun update(@Body request: SaveGroupOverrideRequest) {
        val userId = requireUserId(request.userId)
        val current = GroupOverrideTable.get(userId)
            ?: notFound("per-user billing override for user=$userId not found")
        val remark = normalizeRemark(request.remark)
        val code = requireLiveGroupCode(request.groupCode)
        GroupOverrideTable.update(current.copy(groupCode = code, remark = remark))
    }

    /**
     * 撤销例外。撤销之后该用户回到解析顺序的第 3/4 层，不是「没有计价」。
     *
     * ⚠️ 这是**硬删**：`gateway_group_overrides` 没有 `deleted` 列、[GroupOverride] 也没有
     * `@SoftDelete`（与 [GatewayGroup] 不同），所以 `destroy()` 直接 DELETE 掉这一行。
     * 代价说清楚：撤销之后「这位客户曾经谈过什么价、remark 里写的合同号」在本表里就没有痕迹了。
     *
     * 账务侧现在补得上归因（V007）：`gateway_settlements` 与 `gateway_usage_logs` 各有
     * `group_code` / `group_source` 两列 —— reserve 时固化进结算行，finalize 再转写进日志。所以
     * 撤销之后，「那段时间这位客户按哪个组计价、那个组是令牌覆盖 / 用户例外 / 会员组 / default
     * 哪一层给的」逐笔都还在。光有 `pricing_snapshot` 里的 `ratio` 是分不出来的：四层能给出同一个
     * 倍率，而它们的含义完全不同（口子 / 谈判价 / 套餐 / 标准价）。
     *
     * 仍然查不到的是 `remark`：合同号是本表独有的信息，硬删之后只剩「vip55/USER_OVERRIDE」这个
     * 结果、没有「凭什么谈成这个价」的依据。补齐它要给本表加软删列（与 [GatewayGroup] 同形），
     * 记在 backlog GROUP-OVERRIDE-ADMIN-CRUD-P1 的后续项 4 —— 那是记录问题不是计价问题，
     * 开着不会算错钱。
     */
    @Delete("/delete/{userId}")
    @Permission("gateway:group-override:delete")
    suspend fun delete(@PathVariable userId: Long) {
        GroupOverrideTable.get(userId)
            ?: notFound("per-user billing override for user=$userId not found")
        GroupOverrideTable.destroy(userId)
    }

    private fun requireUserId(userId: Long): Long {
        if (userId <= 0) badRequest("userId must be a positive number")
        return userId
    }

    /**
     * 校验这个 userId 现在是一个能挂例外的会员。判据本身与「port 未绑定时怎么办」都写在
     * [logic.MemberDirectoryPort] / [logic.MemberDirectory]，这里只负责把结果变成 400。
     *
     * **只有 [create] 调它，[update] 与 [delete] 不调** —— 那两个操作的是既成事实：行已经在了，
     * 它的 userId 当初就该校验过。更要紧的是它们必须能操作「会员后来注销了」的那一行：
     * 注销之后运营要的正是删掉这条例外，而按当前 status 拦 delete 会让它删不掉，
     * 于是谈判价永久留在表里、指向一个不再存在的客户。拦写入、不拦清理。
     *
     * 400 而不是 404：与 [requireLiveGroupCode] 对悬空组码的处置一致 —— 两者都是
     * **入参指向了一个不存在的东西**，不是「资源当前状态不允许这个操作」。
     * 消息点名两种可能（id 打错 / 账号不可用）而不合并成一句「无效」，是因为「无效」
     * 读起来像格式错了，运营会去改格式而不是去核对数字。
     */
    private suspend fun requireChargeableMember(userId: Long) {
        val port = MemberDirectory.port ?: return // 未绑定 → 退化，bootstrap 已 warn
        if (!port.isChargeableMember(userId)) badRequest(
            "no chargeable member with userId=$userId; either the id is mistyped or that member is " +
                "disabled / has closed their account. Refusing to write the override: it would sit in " +
                "the table, the resolver would never reach it, and the customer would keep being " +
                "priced at the default group with no error on either side",
        )
    }

    /**
     * 写入时校验组是**活的**，用的谓词与 [logic.BillingGroupResolver] 内部的 `byCode`
     * 完全一致（`GatewayGroupTable.oneWhere { GatewayGroup::code eq code }`）。
     *
     * 必须是同一个谓词：`GatewayGroup` 带 `@SoftDelete val deleted`，生成的 table impl 因此有
     * `softDeleteConfig(deletedColumn = "deleted", notDeletedValue = 0)`，查询路径自动只看得见
     * 未软删的行（harness S26(b) 实证过：把 default 组软删掉，解析器立刻 500
     * `billing_group_config_broken`，不会静默按 1.0 收）。于是「活着」= `oneWhere` 查得到。
     * 两处若用了不同判据 —— 比如这里自己去读 `deleted` 列、或者图省事放宽成「历史上存在过」——
     * 就会写出「写得进去但解析不出来」的行，也就是这条校验要消灭的那个 500。
     *
     * 悬空引用回 400 而不是 409：这是**入参指向了一个不存在的东西**，
     * 不是「资源当前状态不允许这个操作」。消息里写清后果，运营才知道为什么不能先建后配。
     */
    private suspend fun requireLiveGroupCode(raw: String): String {
        val code = raw.trim()
        if (code.isEmpty()) badRequest("groupCode must not be blank")
        if (code.length > 64) badRequest("groupCode must be at most 64 characters")
        GatewayGroupTable.oneWhere { GatewayGroup::code eq code }
            ?: badRequest(
                "no live billing group with code='$code'; refusing to write a dangling override " +
                    "because the resolver rejects it as billing_group_config_broken (HTTP 500) " +
                    "for that user on every request",
            )
        return code
    }

    /** 空串归一成 null：DDL 允许 NULL，留一个空串只会让「没填」与「填了空的」变成两件事。 */
    private fun normalizeRemark(raw: String?): String? {
        val remark = raw?.trim()?.takeIf { it.isNotEmpty() }
        // 超长必须在到达驱动之前拦下：三方言对超长 VARCHAR 的处置不一致
        // （报错 / 静默截断），静默截断会把备注变成半句话且没人知道。
        if (remark != null && remark.length > 256) badRequest("remark must be at most 256 characters")
        return remark
    }

    // ── 业务拒绝一律走标准 4xx（与 GroupController 同一约定）──
    // 抛 IllegalArgumentException 会被 BufferedHttpDispatcher 兜底成 500 + "Internal Server Error"：
    // message 不进响应信封，真正的原因只留在服务端日志里。4xx 的 message 会原样返回，能直接显示给人看。

    private fun notFound(message: String): Nothing =
        throw HttpException(NetonErrorCode.RESOURCE_NOT_FOUND, message)

    /** 该用户已有一条例外（含并发抢建）→ 409。主键就是 userId，所以「第二条」只能是冲突。 */
    private fun alreadyExists(userId: Long): Nothing =
        throw HttpException(
            NetonErrorCode.RESOURCE_ALREADY_EXISTS,
            "user=$userId already has a per-user billing override; update it instead of creating another",
        )

    private fun badRequest(message: String): Nothing =
        throw HttpException(NetonErrorCode.INVALID_PARAMS, message)
}
