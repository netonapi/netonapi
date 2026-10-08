package logic

/**
 * 会员名录 SPI：gateway 不认识 member。
 *
 * 用途只有一个 —— 管理端写**用户级计费例外**（大客户谈判价）时，先问一句「这个 userId 现在
 * 是一个能挂例外的会员吗」。此前问不了：module-gateway 编译期看不到 member（跨模块协作一律走
 * 装配层的 port，见 [MemberGroupPort]），于是 `userId` 只校验了正数。把 10001 打成 100001，
 * 例外照样写成功、接口照样 200，而那位客户仍按 `default` 组计价 —— 谈判价没生效，
 * **双方都不会报错**：客户按标准价付了钱，运营以为给了折扣。
 *
 * 判据由装配层给（与 [MemberGroupPort] 同一套分工：gateway 只问，产品策略在装配层回答），
 * 但必须满足下面两条，否则这个 port 只是把缺陷换个形状留下来：
 *
 *  1. 「查得到这一行」**不等于**「能挂例外」。member 的自助注销只把 `status` 置成 `DELETED`、
 *     **不动 `deleted` 列**（`MemberAuthLogic.deleteOwnAccount`，其 KDoc 明说「记录保留，
 *     后台账号管理仍看得到」），所以 `MemberTable.get(id) != null` 对注销会员依然成立。
 *     只判存在性就等于允许给已注销账号写谈判价，而那几乎一定意味着 id 打错了。
 *  2. 判据写成**白名单**（`status == NORMAL`），不要写成 `status != DELETED`。member 自己的
 *     `MemberStatus` KDoc 用红字标了这条：否定式判断在新增状态时会默默放行，`DELETED`
 *     当初就是这么被漏掉的。
 *
 * 抛异常 = 会员系统故障，**不得折叠成 false**：controller 让它原样上抛成 500。把依赖方故障
 * 说成「没有这个会员」，运营会去反复核对一个其实没错的 id —— 与 [MemberGroupPort] 那条
 * 「抛异常不得表达成 null」是同一纪律。
 */
interface MemberDirectoryPort {
    suspend fun isChargeableMember(userId: Long): Boolean
}

/**
 * 绑定挂载点。装配期可变、服务期只读（与 [BillingGroupResolver.memberPort] 同一套约定）。
 *
 * 未绑定（null）时写入校验退化成「只校验正数」，也就是本 port 要消灭的那个缺陷原样留着 ——
 * 所以 [init.GatewayRuntimeBootstrap] 在 null 时会 warn 一行。这是权衡后的取舍：absent 就
 * **启动失败**，会给「只跑 gateway、不跑 member」的部署凭空加一道墙；absent 就**拒绝写入**，
 * 会让例外功能在没有 member 的部署上整个消失。两者都比退化更难接受，而退化至少是可发现的
 * （有日志），且 NetonAPI 的装配层一定会绑（见 newgate 的 `Main.kt`）。
 */
object MemberDirectory {
    var port: MemberDirectoryPort? = null
}
