package logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenLogicTest {
    @Test
    fun generatedKeyHasSkPrefixAnd48Chars() {
        val key = TokenLogic.generateKey()
        assertTrue(key.startsWith("sk-"))
        assertEquals(51, key.length) // "sk-" + 48
        assertTrue(key.drop(3).all { it.isLetterOrDigit() })
    }

    @Test
    fun displayMasksMiddle() {
        val key = "sk-abcdEFGHijklMNOPqrstUVWX0123456789abcdefghwxyz"
        assertEquals("sk-abcd****wxyz", TokenLogic.display(key))
        assertEquals("****", TokenLogic.display("sk-short"))
    }

    @Test
    fun hashIsStableSha256Hex() {
        val key = "sk-test"
        assertEquals(TokenLogic.hashKey(key), TokenLogic.hashKey(key))
        assertEquals(64, TokenLogic.hashKey(key).length)
    }
}
