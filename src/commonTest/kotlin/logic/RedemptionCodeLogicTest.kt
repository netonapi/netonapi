package logic

import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 兑换码的**纯逻辑**部分：码体形态、归一化，以及支撑「不加限流」那个决定的熵。
 *
 * 不测的两样东西，各有各的归属：
 *  - 三层幂等（状态校验 / 乐观锁占位 / 台账 ref 唯一约束）要 DB，由 harness S36–S39 端到端锁；
 *  - 取样是否均匀是 [neton.security.random.SecureRandom.string] 的职责，它自己有
 *    `SecureRandomTest.string_covers_alphabet_without_modulo_bias`，这里不复制一份。
 *
 * 这里测的恰好是**没人替我测的那部分**：码存明文，整个防枚举设计就压在字符集与码长这两个常数上，
 * 而它们正是别人为了「码短一点好念」最可能顺手改掉的东西。同理，管理端列表的脱敏也压在这里 ——
 * 码在库里是明文，`redact` 是它与「能看列表就能兑换」之间唯一的一层。
 */
class RedemptionCodeLogicTest {

    @Test fun alphabetDropsTheConfusablesAndStaysUppercase() {
        val a = RedemptionCodeLogic.ALPHABET
        assertEquals(31, a.length, "字符集大小变了就得重算熵，见 codeLengthCarriesTheClaimedEntropy")
        // SecureRandom.string 对重复字符是**抛异常**而不是静默降熵 —— 那会让整批生成 500。
        // 在测试里先撞出来，比在运营点「生成 1000 张」时撞出来便宜。
        assertEquals(a.length, a.toSet().size, "字符集里有重复字符：生成会直接抛，不只是降熵")
        // 去掉 0/O、1/I/L 是为人工转录（客服念给客户、客户手抄），不是为熵
        for (c in "0O1IL") assertTrue(c !in a, "混进了易混淆字符 '$c'")
        // normalize 会 uppercase，所以字符集必须全大写：否则展示出去的码归一化后对不上它自己
        assertTrue(a.all { it.isUpperCase() || it.isDigit() }, "字符集含小写，normalize 的 uppercase 会改变码")
    }

    @Test fun codeLengthCarriesTheClaimedEntropy() {
        // KDoc 写的是「20 字符 × log2(31) ≈ 99 bit」，而 app 侧 RedemptionController 明确说
        // 防枚举**只**靠这个熵、不挂限流。写下这个数字却不钉住它，就是「文档说的和代码做的
        // 不一致」的种子 —— 谁改短码长或缩小字符集，这里必须红。
        val bits = RedemptionCodeLogic.CODE_MAX_LEN *
            (ln(RedemptionCodeLogic.ALPHABET.length.toDouble()) / ln(2.0))
        assertTrue(
            bits >= 98.0,
            "码体熵只剩 $bits bit（${RedemptionCodeLogic.CODE_MAX_LEN} 字符 × " +
                "${RedemptionCodeLogic.ALPHABET.length} 字符集），低于设计声称的 98 bit 下限。" +
                "砍掉熵这条腿，就必须同时补上按用户的兑换失败计数。",
        )
    }

    @Test fun randomCodeHasTheRightShapeAndDoesNotRepeat() {
        val a = RedemptionCodeLogic.ALPHABET
        val n = 2000
        val seen = HashSet<String>(n)
        repeat(n) {
            val c = RedemptionCodeLogic.randomCode()
            assertEquals(RedemptionCodeLogic.CODE_MAX_LEN, c.length, "码长不对: '$c'")
            assertTrue(c.all { it in a }, "码体里出现了字符集之外的字符: '$c'")
            seen += c
        }
        // 31^20 ≈ 1e29 的空间里取 2000 张，撞上的概率是 1e-21 量级；真撞了说明随机源退化了
        assertEquals(n, seen.size, "$n 张码里出现了重复，随机源不是 CSPRNG")
    }

    @Test fun normalizeMeetsHowUsersActuallyTypeTheCode() {
        // DB 存无分隔大写裸码，运营发出去的是 4×5 分组形态。用户会带连字符、带空格、小写抄进来 ——
        // 只按字面量比对的话，用户照着邮件输入却兑不了，而后台看那张码明明还是未用。那是必然的客诉。
        val bare = "23456789ABCDEFGHJKMN"
        assertEquals(bare, RedemptionCodeLogic.normalize("23456-789AB-CDEFG-HJKMN"))
        assertEquals(bare, RedemptionCodeLogic.normalize("23456 789ab cdefg hjkmn"))
        assertEquals(bare, RedemptionCodeLogic.normalize("  23456789abcdefghjkmn  "))
        assertEquals("", RedemptionCodeLogic.normalize("   "))
        assertEquals("", RedemptionCodeLogic.normalize(""))
    }

    @Test fun redactKeepsEnoughToIdentifyButNotEnoughToRedeem() {
        // 首尾两组留着，客服才能拿客户念的码对上库里哪一行；中间两组必须消失。
        assertEquals("23456-*****-*****-HJKMN", RedemptionCodeLogic.redact("23456789ABCDEFGHJKMN"))
        // 掩码字符不能来自码体字符集，否则脱敏串看起来像一张真码，运营会照着念给客户。
        assertTrue('*' !in RedemptionCodeLogic.ALPHABET, "掩码字符混进了码体字符集")
        repeat(500) {
            val c = RedemptionCodeLogic.randomCode()
            val r = RedemptionCodeLogic.redact(c)
            // 露出的码体字符数恰好是两组。这条比「原码不是子串」要紧：改 redact 多保一组，
            // 子串断言照样绿（`*` 还在），只有计数会红。
            assertEquals(
                2 * RedemptionCodeLogic.CODE_GROUP_LEN,
                r.count { it in RedemptionCodeLogic.ALPHABET },
                "脱敏后露出的码体字符数不是两组: '$c' -> '$r'",
            )
            // 归一化后必须对不上原码：`*` 会被 normalize 滤掉，粘进兑换框也查不到行。
            assertTrue(
                RedemptionCodeLogic.normalize(r) != c,
                "脱敏串归一化后就是原码，等于没脱敏: '$r'",
            )
            assertTrue(
                RedemptionCodeLogic.normalize(r).length < RedemptionCodeLogic.CODE_MAX_LEN,
                "脱敏串归一化后仍有 ${RedemptionCodeLogic.CODE_MAX_LEN} 字符: '$r'",
            )
        }
        // 少于 3 组时「保首尾」等于全保，所以整体掩掉。randomCode 恒产出 20 字符，
        // 这一支只可能由脏数据触发 —— 但脱敏函数宁可少给，不能多给。
        assertEquals("*****-**", RedemptionCodeLogic.redact("2345678"))
        assertEquals("*****", RedemptionCodeLogic.redact("23456"))
        assertEquals("", RedemptionCodeLogic.redact(""))
    }

    @Test fun formatGroupsByFiveAndNormalizeUndoesIt() {
        assertEquals("23456-789AB-CDEFG-HJKMN", RedemptionCodeLogic.format("23456789ABCDEFGHJKMN"))
        // 往返恒等比上面那条字面量更要紧：它是「运营发出去的码用户兑不了」这类客诉的唯一守卫。
        // 用真实生成的码而不是手写样本 —— 手写样本只会覆盖我想到的形态。
        repeat(500) {
            val c = RedemptionCodeLogic.randomCode()
            assertEquals(c, RedemptionCodeLogic.normalize(RedemptionCodeLogic.format(c)), "往返对不上: '$c'")
        }
    }
}
