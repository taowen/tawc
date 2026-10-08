package me.phie.tawc.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtyOfTest {
    @Test
    fun parsesPastParenthesesInComm() {
        assertEquals(34816, ttyOf("1234 (a) b (c)) S 1 1234 4321 34816 1234 0"))
        assertEquals(0, ttyOf("88 (bash) S 77 88 77 0 88 4194304"))
        assertNull(ttyOf("garbage"))
    }
}
