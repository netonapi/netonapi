package logic

import kotlinx.coroutines.CancellationException
import model.RedemptionCode
import neton.core.http.HttpException
import neton.core.http.NetonErrorCode
import neton.database.dsl.*
import neton.logging.Logger
import neton.security.random.SecureRandom
import table.RedemptionCodeTable

/**
 * 兑换码：批量生成与核销入账。
 *
 * 这是运营侧的营收工具（线下卖码 / 活动发放 / 客诉补偿），也是台账里 `type='redeem'` 唯一的产生方 ——
 * [model.QuotaTransaction] 自 V001 起就预留了该类型与「ref = 兑换码」，此前一直是空位。
 *
 * 核销的幂等有三层，与 [QuotaRechargeLogic.markPaid] 同构：互相独立，任一失效另一层兜底。
 *  ① 兑换前的状态与有效期校验 —— 挡住绝大多数重复提交，并给出人能看懂的原因；
 *  ② `UPDATE ... WHERE status = 未用` 的乐观锁占位 —— 并发兑同一码只有一个请求能拿到 1 行；
 *  ③ 台账 ref 的唯一约束 `uk_gateway_quota_tx_ref`（ref = `redeem:{code}`）—— 即使 ② 被绕过，
 *     第二次 grant 撞约束会让**整个事务回滚**，码状态随之退回未用，不会「码没了钱也没到」。
 *
 * 码在日志里只打前缀（[mask]）：码是 bearer 凭据，谁拿到谁能兑，写全码进日志等于把额度摊在日志里。
 */
@neton.core.annotations.Logic(logger = "logic.gateway-redemption")
class RedemptionCodeLogic(private val log: Logger, private val quotaLogic: QuotaLogic) {

    /** 生成结果。[codes] 是**展示形态**（分组带连字符），运营可直接复制发给客户。 */
    data class Generated(
        val batchId: String,
        val codes: List<String>,
        val quotaMicro: Long,
        val expiresAt: Long?,
        val note: String?,
    )

    /** 兑换结果。[balance] 是入账后的余额，供前端直接刷新显示。 */
    data class Redeemed(val balance: Long, val quotaMicro: Long, val ref: String, val codeId: Long)

    /** 批次核销情况。[expired] 与 [unused] 互斥：过期但状态仍未用的单独算，运营要能看出「没卖完但已失效」。 */
    data class BatchStats(
        val batchId: String,
        val total: Int,
        val unused: Int,
        val used: Int,
        val disabled: Int,
        val expired: Int,
        val quotaMicro: Long,
        val redeemedMicro: Long,
    )

    /**
     * 批量生成。
     *
     * 面额只由管理员指定、兑换时不接受客户端改写 —— 客户端能填面额就等于自己给自己定价
     * （与 [QuotaRechargeLogic.create] 只收 price 不收 amount 同一条理由）。
     *
     * 单次上限 [MAX_BATCH]：不限量等于允许一个请求插入任意多行。
     * 生成**不包在一个大事务里**：码之间彼此独立，部分成功没有一致性问题（不同于转账），
     * 而千行大事务会长时间持锁。失败时异常消息带上 batchId，运营据此能查到已生成的那部分。
     */
    suspend fun generate(
        quotaMicro: Long,
        count: Int,
        expiresAt: Long?,
        note: String?,
        createdBy: Long?,
    ): Generated {
        if (quotaMicro <= 0) throw badRequest("面额必须为正数（μUSD）")
        if (count <= 0) throw badRequest("生成数量必须为正数")
        if (count > MAX_BATCH) throw badRequest("单次最多生成 $MAX_BATCH 张，实际请求 $count")
        if (expiresAt != null && expiresAt <= nowMillis()) throw badRequest("过期时间必须晚于当前时刻")
        val trimmedNote = note?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedNote != null && trimmedNote.length > NOTE_MAX) {
            throw badRequest("备注最多 $NOTE_MAX 个字符")
        }

        val batchId = newBatchId()
        val codes = ArrayList<String>(count)
        try {
            repeat(count) { codes += insertOne(quotaMicro, expiresAt, trimmedNote, createdBy, batchId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 带上 batchId：这一批可能已经部分入库，运营要能按批次把已生成的捞出来，
            // 否则一次失败就留下一批谁也找不到的码。
            log.error("redemption batch failed partway batch=$batchId generated=${codes.size}/$count", cause = e)
            throw IllegalStateException("生成兑换码失败（批次 $batchId 已生成 ${codes.size}/$count 张，可按批次号查询）: ${e.message}", e)
        }
        log.info(
            "redemption batch created batch=$batchId count=$count quota=$quotaMicro " +
                "expiresAt=$expiresAt operator=$createdBy"
        )
        return Generated(batchId, codes, quotaMicro, expiresAt, trimmedNote)
    }

    /**
     * 用户兑换。
     *
     * 三种失败原因分开报（已用 / 已作废 / 已过期），客服需要区分；但**一律不回面额** ——
     * 否则枚举命中的收益会从「知道这个码存在」升级成「知道这张值多少钱」。码体约 99 bit，
     * 枚举本身不可行，这里只是不把额外信息白送。
     */
    suspend fun redeem(userId: Long, rawCode: String): Redeemed {
        val code = normalize(rawCode)
        if (code.isEmpty()) throw badRequest("请输入兑换码")
        if (code.length > CODE_MAX_LEN) throw badRequest("兑换码格式不正确")

        val db = neton.database.dbContext()
        var balance = 0L
        var amount = 0L
        var codeId = 0L
        db.transaction {
            val row = RedemptionCodeTable.oneWhere { RedemptionCode::code eq code }
                ?: throw notFound("兑换码无效")
            codeId = row.id
            when (row.status) {
                STATUS_USED -> throw conflict("兑换码已被使用")
                STATUS_DISABLED -> throw conflict("兑换码已作废")
            }
            val exp = row.expiresAt
            if (exp != null && exp <= nowMillis()) throw conflict("兑换码已过期")
            amount = row.quotaMicro

            // ② 乐观锁占位：并发第二个请求在这里拿到 0 行 → 抛冲突退出，不会重复入账。
            //    裸 SQL 而非 update(entity)：条件里必须带 status，否则占位就失去意义。
            val claimed = db.execute(
                "UPDATE gateway_redemption_codes SET status = :used, used_user_id = :uid, " +
                    "used_at = :ts, updated_at = :ts WHERE id = :id AND status = :unused",
                mapOf(
                    "used" to STATUS_USED,
                    "uid" to userId,
                    "ts" to nowMillis(),
                    "id" to row.id,
                    "unused" to STATUS_UNUSED,
                ),
            )
            if (claimed == 0L) throw conflict("兑换码已被使用")
            // ③ 台账 ref 唯一约束是最后一道闸。type='redeem' 是 V001 就预留好的那一个。
            balance = quotaLogic.grant(userId, amount, type = TX_TYPE, ref = REF_PREFIX + code)
        }
        log.info(
            "redemption code redeemed id=$codeId user=$userId amount=$amount balance=$balance code=${mask(code)}"
        )
        return Redeemed(balance, amount, REF_PREFIX + code, codeId)
    }

    /**
     * 作废一张码。只能作废**未用**的。
     *
     * 已用的是历史事实，改它等于篡改账务。要收回已发放的额度，走管理端的额度调整另记一条台账，
     * 不要把码状态改回去 —— 状态一旦能往回走，「一码一次」这个不变量就没有可信的来源了。
     */
    suspend fun disable(id: Long): RedemptionCode {
        val db = neton.database.dbContext()
        val claimed = db.execute(
            "UPDATE gateway_redemption_codes SET status = :dis, updated_at = :ts " +
                "WHERE id = :id AND status = :unused",
            mapOf("dis" to STATUS_DISABLED, "ts" to nowMillis(), "id" to id, "unused" to STATUS_UNUSED),
        )
        if (claimed == 0L) {
            val row = RedemptionCodeTable.get(id) ?: throw notFound("兑换码不存在: $id")
            throw conflict(
                if (row.status == STATUS_USED) "兑换码已被使用（用户 ${row.usedUserId}），不能作废"
                else "兑换码已经是作废状态"
            )
        }
        log.info("redemption code disabled id=$id")
        return RedemptionCodeTable.get(id) ?: throw notFound("兑换码不存在: $id")
    }

    /**
     * 整批作废（只影响未用的）。运营发现某场活动的码外泄时用它止损。
     * 返回实际作废的张数 —— 0 是合法结果（这批本来就全用完了），不是错误。
     */
    suspend fun disableBatch(batchId: String): Int {
        val bid = batchId.trim()
        if (bid.isEmpty()) throw badRequest("批次号不能为空")
        val n = neton.database.dbContext().execute(
            "UPDATE gateway_redemption_codes SET status = :dis, updated_at = :ts " +
                "WHERE batch_id = :b AND status = :unused",
            mapOf(
                "dis" to STATUS_DISABLED,
                "ts" to nowMillis(),
                "b" to bid,
                // :unused 在 WHERE 里 —— 漏掉它 sqlx4k 会抛 NamedParameterValueNotSupplied，
                // 于是「批量止损」这个动作本身 500，而外泄的码还活着。
                "unused" to STATUS_UNUSED,
            ),
        )
        log.warn("redemption batch disabled batch=$bid count=$n")
        return n.toInt()
    }

    /**
     * 批次核销情况。内存聚合而非 SQL 聚合：单批最多 [MAX_BATCH] 行，
     * 而 COUNT/SUM 的写法在三种方言上并不完全一致，没必要为省几行引入方言分叉。
     */
    suspend fun stats(batchId: String): BatchStats {
        val bid = batchId.trim()
        if (bid.isEmpty()) throw badRequest("批次号不能为空")
        val rows = RedemptionCodeTable.query { where { RedemptionCode::batchId eq bid } }.list()
        if (rows.isEmpty()) throw notFound("批次不存在: $bid")
        val now = nowMillis()
        val used = rows.count { it.status == STATUS_USED }
        val disabled = rows.count { it.status == STATUS_DISABLED }
        val expired = rows.count { it.status == STATUS_UNUSED && it.expiresAt != null && it.expiresAt <= now }
        val unused = rows.size - used - disabled - expired
        return BatchStats(
            batchId = bid,
            total = rows.size,
            unused = unused,
            used = used,
            disabled = disabled,
            expired = expired,
            quotaMicro = rows.first().quotaMicro,
            redeemedMicro = rows.filter { it.status == STATUS_USED }.sumOf { it.quotaMicro },
        )
    }

    /** 插入一张码，撞唯一约束就换码重试（见 [randomCode] 的熵说明：重试几乎不会真的触发）。 */
    private suspend fun insertOne(
        quotaMicro: Long,
        expiresAt: Long?,
        note: String?,
        createdBy: Long?,
        batchId: String,
    ): String {
        var lastError: Throwable? = null
        repeat(MAX_INSERT_RETRY) {
            val candidate = randomCode()
            // 预检把重试概率压到几乎为零：批量生成时同一批内部也可能撞（虽然概率同样极低）。
            if (RedemptionCodeTable.existsWhere { RedemptionCode::code eq candidate }) return@repeat
            try {
                RedemptionCodeTable.insert(
                    RedemptionCode(
                        code = candidate,
                        quotaMicro = quotaMicro,
                        status = STATUS_UNUSED,
                        expiresAt = expiresAt,
                        batchId = batchId,
                        note = note,
                        createdBy = createdBy,
                    )
                )
                return format(candidate)
            } catch (e: CancellationException) {
                throw e // 协程取消必须原样上抛
            } catch (e: Throwable) {
                // **不解析驱动的错误文本**（方言无关，与 GroupController 的并发抢建同一取舍）：
                // 插入失败后回查同码的行，查得到就是撞码 → 换码重试；查不到说明是真故障
                // （连接断了、约束名不对、磁盘满了）→ 原样上抛，不把它伪装成「再试一次就好」。
                if (RedemptionCodeTable.existsWhere { RedemptionCode::code eq candidate }) {
                    lastError = e
                    log.warn("redemption code collision, retrying batch=$batchId code=${mask(candidate)}")
                    return@repeat
                }
                throw e
            }
        }
        throw IllegalStateException(
            "生成兑换码连续 $MAX_INSERT_RETRY 次撞码（批次 $batchId），这不该发生: ${lastError?.message}"
        )
    }

    /** 批次号：只是分组标识，不参与任何唯一性判定，64 bit 随机足够。 */
    private fun newBatchId(): String = "rc-" + SecureRandom.hex(8)

    /** 只留前 4 位：够运营对上号，不够别人拿去兑换。 */
    private fun mask(code: String): String = code.take(LOG_CODE_PREFIX) + "…"

    private fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    // ── 业务拒绝一律走标准 4xx（与 GroupController、payment 的 WalletHttpErrors 同一约定）──
    // 抛 IllegalArgumentException 会被 BufferedHttpDispatcher 兜底成 500 + "Internal Server Error"，
    // message 不进信封 —— 用户点了「兑换」只会看到一片空白，而原因其实是「这张码已经用过了」。

    private fun badRequest(message: String): Nothing =
        throw HttpException(NetonErrorCode.INVALID_PARAMS, message)

    private fun notFound(message: String): Nothing =
        throw HttpException(NetonErrorCode.RESOURCE_NOT_FOUND, message)

    private fun conflict(message: String): Nothing =
        throw HttpException(NetonErrorCode.OPERATION_CONFLICT, message)

    companion object {
        const val STATUS_UNUSED = 0
        const val STATUS_USED = 1
        const val STATUS_DISABLED = 2

        /** 台账类型。V001 的 QuotaTransaction 注释里预留的就是这个字面量。 */
        const val TX_TYPE = "redeem"

        /**
         * 台账 ref 前缀。与 `gateway:quota:`（充值）、`wallet:recharge:`（钱包）、`mock:` 同一惯例：
         * ref 空间由多个业务共用一个唯一索引，前缀保证它们互不碰撞。
         */
        const val REF_PREFIX = "redeem:"

        const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
        const val CODE_GROUPS = 4
        const val CODE_GROUP_LEN = 5
        const val GROUP_SEP = "-"

        /** 裸码长度（不含分隔符），也是 code 列的实际上限。 */
        const val CODE_MAX_LEN = CODE_GROUPS * CODE_GROUP_LEN

        private const val MAX_INSERT_RETRY = 5
        private const val LOG_CODE_PREFIX = 4

        const val MAX_BATCH = 1000
        private const val NOTE_MAX = 255

        /**
         * 用户输入 → DB 里的裸码形态。
         *
         * 展示时是分组带连字符的（[format]），所以用户会带连字符、带空格、或大小写混着抄进来。
         * DB 存的是**无分隔的大写裸码**，两边必须归一到同一形态才比得上 —— 只按字面量比对的话，
         * 运营发出去的码用户照着输入却兑不了，而且从后台看那张码明明还是未用。
         */
        fun normalize(raw: String): String = raw.uppercase().filter { it.isLetterOrDigit() }

        /** 裸码 → 展示形态（4 组 × 5 字符）。DB 里不存这个形态。 */
        fun format(code: String): String = code.chunked(CODE_GROUP_LEN).joinToString(GROUP_SEP)

        /**
         * 裸码 → 管理端列表用的**脱敏**形态：保首尾两组，中间两组换成 `*`。
         *
         * 为什么不干脆全掩：客服在工单里要能拿客户念的码对上库里哪一行，全掩之后同一批一千张
         * 长得一模一样，只能靠 id 找 —— 而客户不会报 id。首尾两组是 10 字符 / 31 字符集
         * ≈ 49 bit，千张批次里定位一张绰绰有余，也仍远低于兑换所需的 99 bit。
         *
         * `*` 不在 [ALPHABET] 里，所以脱敏串不可能被当成一张真码：即使有人把它粘进兑换框，
         * [normalize] 会把 `*` 滤掉（它不是字母数字），只剩 10 个字符，长度就对不上
         * [CODE_MAX_LEN] 的裸码，查不到任何行。
         *
         * 与私有的 `mask()`（日志用，只留前 4 位）不是一个用途，别合并：日志要的是**最少**披露，
         * 列表要的是**可定位**，而且得保留分组结构，才能和客户电话里念出来的那串逐组对齐。
         *
         * 少于 3 组时「保首尾」就等于全保，那种码整体掩掉 —— 脱敏函数宁可少给，不能多给。
         * [randomCode] 恒产出 [CODE_MAX_LEN] 个字符，所以这一支只可能由脏数据触发。
         */
        fun redact(code: String): String {
            val groups = code.chunked(CODE_GROUP_LEN)
            if (groups.size < 3) return groups.joinToString(GROUP_SEP) { "*".repeat(it.length) }
            return groups
                .mapIndexed { i, g -> if (i == 0 || i == groups.lastIndex) g else "*".repeat(g.length) }
                .joinToString(GROUP_SEP)
        }

        /**
         * 码体字符集：31 个字符，去掉 `0/O/1/I/L`。
         *
         * 去掉它们不是为了熵（少 5 个字符只损失约 2 bit），而是因为码要经人工转录 ——
         * 客服在电话里念给客户、客户手抄到纸上，`0` 与 `O`、`1` 与 `I` 与 `L` 分不清就会白跑一趟。
         * 20 字符 × log2(31) ≈ 99 bit，暴力枚举不可行（这也是 code 敢存明文的前提）。
         *
         * 这个熵是 app 侧 RedemptionController「不加限流」那个决定的**唯一支撑**，因此由
         * RedemptionCodeLogicTest 钉住：缩短码长或缩小字符集会让测试红 —— 那时就必须同时补上
         * 按用户的失败计数。熵与限流是同一个防护的两条腿，砍掉一条另一条必须补上。
         *
         * 取样委托 [SecureRandom.string]，不自己写拒绝采样。256 不是 31 的整数倍，朴素 `v % 31`
         * 会让前 8 个字符的概率是 9/256 而非 8/256；框架那份按 ceil(log2(n)) 位宽取样并拒绝越界值，
         * 位宽随字符集大小自适应，而且自带均匀性测试（SecureRandomTest）。自己写一份的话，
         * 「上界 = 256 - 256 % 31」这个常数会硬绑在 31 上 —— 哪天字符集增删一个字符，偏置就静默
         * 回来了，而注释还写着「已知的偏差没有理由留着」。发钱的代码不该留这种陷阱。
         *
         * 放在 companion 而非实例方法：它是纯函数（不碰 DB、不碰日志），与 [normalize]/[format] 同属
         * 「码的形态」这一类；也让测试不必构造整个 logic（构造要 QuotaLogic，那会拖进 DB）。
         */
        internal fun randomCode(): String = SecureRandom.string(CODE_MAX_LEN, ALPHABET)
    }
}
