package logic

/** 解析结果：基础档（= 第一档）、分档（单档为 null），以及解析时略过的内容说明。 */
data class ParsedPrice(val base: PriceTier, val tiers: List<PriceTier>?, val notes: List<String> = emptyList())

/**
 * basellm/llm-metadata（new-api「官方倍率预设」）的计费表达式解析器。
 *
 * 只认两种形状，其余一律失败（列入校准结果的「无法解析」，不写入）：
 *   tier("<name>", p * 2.5 + cr * 1.25 + c * 10)
 *   len <= N ? tier(...) : <同形状>        （else 分支可再嵌套，可加括号）
 * 变量：p 输入、c 输出、cr 缓存读、cc 缓存写、cc1h 1 小时缓存写（不计价，记入 notes）；
 * 系数单位 USD / 1M tokens；len 为输入 token 数。宁可拒绝也不猜：认不出的写法不能变成错的价格。
 */
object BillingExprParser {

    fun parse(expr: String): Result<ParsedPrice> = runCatching {
        val p = Parser(tokenize(expr))
        val notes = mutableListOf<String>()
        val tiers = p.expr(notes)
        p.expectEnd()
        val list = if (tiers.size == 1) null else tiers
        ParsedPrice(base = tiers.first().copy(maxPromptTokens = null), tiers = list, notes = notes.distinct())
    }

    private sealed interface Tok {
        data class Word(val s: String) : Tok
        data class Num(val s: String) : Tok
        data class Str(val s: String) : Tok
        data class Sym(val s: String) : Tok
    }

    private fun tokenize(s: String): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                ch.isWhitespace() -> i++
                ch.isLetter() || ch == '_' -> { val j = scan(s, i) { it.isLetterOrDigit() || it == '_' }; out += Tok.Word(s.substring(i, j)); i = j }
                ch.isDigit() || ch == '.' -> { val j = scan(s, i) { it.isDigit() || it == '.' }; out += Tok.Num(s.substring(i, j)); i = j }
                ch == '"' -> { val j = s.indexOf('"', i + 1); require(j > i) { "unterminated string" }; out += Tok.Str(s.substring(i + 1, j)); i = j + 1 }
                s.startsWith("<=", i) -> { out += Tok.Sym("<="); i += 2 }
                ch in "()?:,+*" -> { out += Tok.Sym(ch.toString()); i++ }
                else -> error("unexpected '$ch'")
            }
        }
        return out
    }

    private inline fun scan(s: String, from: Int, ok: (Char) -> Boolean): Int {
        var j = from
        while (j < s.length && ok(s[j])) j++
        return j
    }

    private class Parser(val t: List<Tok>) {
        var i = 0
        fun peek(): Tok? = t.getOrNull(i)
        fun sym(s: String) { val x = t.getOrNull(i++); require(x == Tok.Sym(s)) { "expected '$s'" } }
        fun expectEnd() = require(i == t.size) { "trailing tokens" }

        /** 返回按门槛排好的各档；最后一档 maxPromptTokens = null。 */
        fun expr(notes: MutableList<String>): List<PriceTier> {
            val x = peek()
            if (x == Tok.Sym("(")) { i++; val r = expr(notes); sym(")"); return r }
            if (x == Tok.Word("len")) {
                i++; sym("<=")
                val n = (t.getOrNull(i++) as? Tok.Num)?.s?.toLongOrNull() ?: error("threshold")
                sym("?")
                val low = expr(notes)
                require(low.size == 1) { "then-branch must be a single tier" }
                sym(":")
                val rest = expr(notes)
                require(rest.none { it.maxPromptTokens != null && it.maxPromptTokens <= n }) { "thresholds must increase" }
                return listOf(low.single().copy(maxPromptTokens = n)) + rest
            }
            return listOf(tier(notes))
        }

        fun tier(notes: MutableList<String>): PriceTier {
            require(peek() == Tok.Word("tier")) { "expected tier(...)" }
            i++; sym("(")
            require(t.getOrNull(i++) is Tok.Str) { "tier name" }
            sym(",")
            val coef = mutableMapOf<String, String>()
            while (true) {
                val a = t.getOrNull(i++); sym("*"); val b = t.getOrNull(i++)
                val (v, n) = when {
                    a is Tok.Word && b is Tok.Num -> a.s to b.s
                    a is Tok.Num && b is Tok.Word -> b.s to a.s
                    else -> error("term")
                }
                require(v in VARS) { "unknown variable $v" }
                if (v == "cc1h") notes += "cc1h ignored" else coef[v] = PricingCalc.requirePrice(n)
                if (peek() == Tok.Sym("+")) { i++; continue }
                break
            }
            sym(")")
            return PriceTier(
                maxPromptTokens = null,
                input = coef["p"] ?: "0", output = coef["c"] ?: "0",
                cacheRead = coef["cr"] ?: "0", cacheWrite = coef["cc"] ?: "0",
            )
        }
    }

    private val VARS = setOf("p", "c", "cr", "cc", "cc1h")
}
