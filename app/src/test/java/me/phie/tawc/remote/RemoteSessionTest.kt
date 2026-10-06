package me.phie.tawc.remote

import me.phie.tawc.session.Reason
import me.phie.tawc.session.SessionHolds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteSessionTest {
    private class FakeNative : RemoteNative {
        var status = """{"state":"stopped"}"""
        var starts = 0
        var stops = 0
        var accept = true

        override fun start(request: String): String? {
            if (!accept) return "bad relay"
            starts++
            status = """{"state":"connecting"}"""
            return null
        }

        override fun stop() {
            stops++
            status = status.replace("\"connecting\"", "\"stopped\"").replace("\"ready\"", "\"stopped\"")
        }

        override fun status() = status
    }

    private lateinit var fake: FakeNative

    @Before
    fun setUp() {
        SessionHolds.resetForTest()
        RemoteSession.resetForTest()
        fake = FakeNative()
        RemoteSession.native = fake
        RemoteSession.executor = java.util.concurrent.Executor { it.run() }
    }

    @After
    fun tearDown() {
        RemoteSession.resetForTest()
        SessionHolds.resetForTest()
    }

    private fun event(kind: String, msg: String = "") =
        RemoteSession.onNativeEvent("""{"time":1,"kind":"$kind","from":"1.2.3.4:5","msg":"$msg"}""")

    @Test
    fun holdFollowsTheAgent() {
        assertTrue(SessionHolds.reasons.value.isEmpty())
        assertNull(RemoteSession.start("arch", "{}"))
        assertEquals(listOf<Reason>(Reason.Remote("arch", 0)), SessionHolds.reasons.value)
        assertTrue(RemoteSession.state.value.running)

        fake.status = """{"state":"ready","clients":2,"secret":"a-b-123","command":"ssh x"}"""
        event("status")
        assertEquals(listOf<Reason>(Reason.Remote("arch", 2)), SessionHolds.reasons.value)
        assertEquals("ssh x", RemoteSession.state.value.status.command)

        // Only one agent at a time.
        assertNotNull(RemoteSession.start("debian", "{}"))
        assertEquals(1, fake.starts)

        RemoteSession.stop()
        assertTrue(SessionHolds.reasons.value.isEmpty())
        assertFalse(RemoteSession.state.value.running)
        assertEquals("arch", RemoteSession.state.value.distroId)
        assertNull(RemoteSession.start("debian", "{}"))
    }

    @Test
    fun agentEndingOnItsOwnReleasesAndReaps() {
        RemoteSession.start("arch", "{}")
        fake.status = """{"state":"stopped","error":"closed after 5 min with no connection"}"""
        event("status")
        assertTrue(SessionHolds.reasons.value.isEmpty())
        assertEquals(1, fake.stops)
        assertEquals("closed after 5 min with no connection", RemoteSession.state.value.status.error)
        assertNull(RemoteSession.start("arch", "{}"))
    }

    @Test
    fun refusedStartHoldsNothing() {
        fake.accept = false
        assertNotNull(RemoteSession.start("arch", "{}"))
        assertTrue(SessionHolds.reasons.value.isEmpty())
    }

    @Test
    fun connectionEventsDontTouchState() {
        RemoteSession.start("arch", "{}")
        val before = RemoteSession.state.value
        event("login", "authenticated (secret)")
        assertEquals(before, RemoteSession.state.value)
    }

    @Test
    fun stopForOnlyTouchesItsDistro() {
        RemoteSession.start("arch", "{}")
        RemoteSession.stopFor("debian")
        assertEquals(0, fake.stops)
        RemoteSession.stopFor("arch")
        assertEquals(1, fake.stops)
    }

    @Test
    fun summaryCountsClients() {
        val s = me.phie.tawc.session.SessionSummary.of(listOf(Reason.Remote("arch", 3), Reason.Terminal("arch")))
        assertTrue(s.remote)
        assertEquals(3, s.remoteClients)
        assertEquals(1, s.terminals)
    }
}
