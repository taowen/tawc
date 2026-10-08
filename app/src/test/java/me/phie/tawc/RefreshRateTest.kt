package me.phie.tawc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefreshRateTest {
    @Test
    fun `picks the fastest mode`() {
        assertEquals(120_000, RefreshRate.maxUsableMhz(listOf(60f, 120f, 30f, 60f)))
        assertEquals(59_940, RefreshRate.maxUsableMhz(listOf(59.94f)))
    }

    @Test
    fun `implausible rates are dropped`() {
        assertEquals(90_000, RefreshRate.maxUsableMhz(listOf(90f, 1000f, 0f)))
        assertNull(RefreshRate.maxUsableMhz(listOf(0f, 1000f)))
        assertNull(RefreshRate.maxUsableMhz(emptyList()))
    }
}
