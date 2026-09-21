package me.phie.tawc.install

import org.junit.Assert.assertEquals
import org.junit.Test

class UserRootfsSessionLabelTest {
    @Test
    fun labelIsProgramBasename() {
        assertEquals("shell", UserRootfsSession.commandLabel(null))
        assertEquals("shell", UserRootfsSession.commandLabel("  "))
        assertEquals("htop", UserRootfsSession.commandLabel("htop -d 5"))
        assertEquals("firefox", UserRootfsSession.commandLabel("/usr/bin/firefox --no-remote"))
        assertEquals("gedit", UserRootfsSession.commandLabel("GDK_BACKEND=wayland gedit"))
    }
}
