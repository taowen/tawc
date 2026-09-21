package me.phie.tawc.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionHoldsTest {
    private var starts = 0

    @Before
    fun setUp() {
        SessionHolds.resetForTest()
        starts = 0
        SessionHolds.starter = { starts++ }
    }

    @After
    fun tearDown() = SessionHolds.resetForTest()

    @Test
    fun acquireStartsServiceUntilItReportsAlive() {
        val a = SessionHolds.acquire(Reason.Terminal("arch"))
        assertEquals(1, starts)
        // Start refused (background): the next acquire retries.
        SessionHolds.acquire(Reason.Command("htop"))
        assertEquals(2, starts)
        SessionHolds.serviceStarted(this)
        SessionHolds.acquire(Reason.Command("top"))
        assertEquals(2, starts)
        a.release()
    }

    @Test
    fun releaseIsIdempotent() {
        val a = SessionHolds.acquire(Reason.Terminal("arch"))
        val b = SessionHolds.acquire(Reason.Terminal("arch"))
        a.release()
        a.release()
        assertEquals(listOf<Reason>(Reason.Terminal("arch")), SessionHolds.reasons.value)
        b.release()
        assertTrue(SessionHolds.reasons.value.isEmpty())
    }

    @Test
    fun updateReplacesReasonAndIgnoresReleasedHolds() {
        val c = SessionHolds.acquire(Reason.Compositor(0))
        c.update(Reason.Compositor(3))
        assertEquals(listOf<Reason>(Reason.Compositor(3)), SessionHolds.reasons.value)
        c.release()
        c.update(Reason.Compositor(5))
        assertTrue(SessionHolds.reasons.value.isEmpty())
    }

    @Test
    fun stopOnlyWhenIdleAndAcquireAfterStopRestarts() {
        SessionHolds.serviceStarted(this)
        val a = SessionHolds.acquire(Reason.Terminal("arch"))
        assertEquals(0, starts)
        assertFalse(SessionHolds.serviceStopIfIdle(this))
        a.release()
        assertTrue(SessionHolds.serviceStopIfIdle(this))
        SessionHolds.acquire(Reason.Terminal("arch"))
        assertEquals(1, starts)
    }

    @Test
    fun oldInstanceDestroyDoesNotUnregisterSuccessor() {
        val old = Any()
        val next = Any()
        SessionHolds.serviceStarted(old)
        assertTrue(SessionHolds.serviceStopIfIdle(old))
        SessionHolds.acquire(Reason.Terminal("arch"))
        SessionHolds.serviceStarted(next)
        // The old instance's onDestroy arrives late.
        SessionHolds.serviceStopped(old)
        SessionHolds.acquire(Reason.Command("htop"))
        assertEquals(1, starts)
    }

    @Test
    fun summaryAggregatesReasons() {
        val s = SessionSummary.of(
            listOf(
                Reason.Terminal("arch"),
                Reason.Terminal("debian"),
                Reason.Compositor(3),
                Reason.Command("htop"),
                Reason.Stray(2),
            ),
        )
        assertEquals(SessionSummary(terminals = 2, windows = 3, commands = listOf("htop"), strays = 2), s)
    }
}
