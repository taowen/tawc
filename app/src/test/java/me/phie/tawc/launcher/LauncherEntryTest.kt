package me.phie.tawc.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LauncherEntry.parseList] against the Rust scanner's JSON shape. */
class LauncherEntryTest {

    @Test
    fun parsesFullEntry() {
        val json = """
            [{
              "id": "firefox",
              "name": "Firefox",
              "comment": "Browse the web",
              "exec": "firefox --new-window",
              "terminal": false,
              "iconPath": "/data/rootfs/usr/share/icons/hicolor/128x128/apps/firefox.png",
              "path": "/data/rootfs/usr/share/applications/firefox.desktop"
            }]
        """.trimIndent()
        val entries = LauncherEntry.parseList(json)
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("firefox", e.id)
        assertEquals("Firefox", e.name)
        assertEquals("firefox --new-window", e.exec)
        assertEquals("/data/rootfs/usr/share/applications/firefox.desktop", e.path)
    }

    @Test
    fun missingPathParsesToEmpty() {
        // Pre-path scanner output (or hand-written test JSON) must not fail.
        val entries = LauncherEntry.parseList("""[{"id": "xterm", "exec": "xterm"}]""")
        assertEquals(1, entries.size)
        assertEquals("", entries[0].path)
    }

    @Test
    fun malformedJsonYieldsEmptyList() {
        assertTrue(LauncherEntry.parseList("not json").isEmpty())
        assertTrue(LauncherEntry.parseList(null).isEmpty())
        assertTrue(LauncherEntry.parseList("").isEmpty())
    }

    private fun entry(id: String, name: String, comment: String = "") =
        LauncherEntry(id, name, comment, exec = id, terminal = false, iconPath = "")

    @Test
    fun filterDropsHiddenUnlessShowHidden() {
        val entries = listOf(entry("a", "Alpha"), entry("b", "Beta"))
        assertEquals(
            listOf("b"),
            LauncherEntry.filter(entries, setOf("a"), showHidden = false, query = "").map { it.id },
        )
        assertEquals(
            listOf("a", "b"),
            LauncherEntry.filter(entries, setOf("a"), showHidden = true, query = "").map { it.id },
        )
    }

    @Test
    fun filterRanksNamePrefixMatchesFirst() {
        // Pre-sorted by name like scanner output; the substring match
        // sorts first alphabetically but must rank below the prefix match.
        val entries = listOf(entry("wf", "AwesomeFire"), entry("firefox", "Firefox"))
        assertEquals(
            listOf("firefox", "wf"),
            LauncherEntry.filter(entries, emptySet(), showHidden = false, query = "fire").map { it.id },
        )
    }

    @Test
    fun filterMatchesIdAndCommentCaseInsensitively() {
        val entries = listOf(
            entry("org.gnome.Calculator", "Calculator"),
            entry("editor", "Files", comment = "Browse EVERYTHING"),
            entry("other", "Other"),
        )
        assertEquals(
            listOf("org.gnome.Calculator"),
            LauncherEntry.filter(entries, emptySet(), showHidden = false, query = "gnome").map { it.id },
        )
        assertEquals(
            listOf("editor"),
            LauncherEntry.filter(entries, emptySet(), showHidden = false, query = "everything").map { it.id },
        )
    }

    @Test
    fun filterTrimsQueryWhitespace() {
        val entries = listOf(entry("a", "Alpha"))
        assertEquals(entries, LauncherEntry.filter(entries, emptySet(), showHidden = false, query = "  "))
    }

    private fun builtin(kind: LauncherEntry.Builtin, name: String) =
        LauncherEntry(kind.id, name, "", "", terminal = kind.opensTerminal, iconPath = "", builtin = kind)

    private val builtins = listOf(
        builtin(LauncherEntry.Builtin.TERM, "TAWC Term"),
        builtin(LauncherEntry.Builtin.UPDATE, "Update packages"),
        builtin(LauncherEntry.Builtin.ADD_ENTRY, "Add entry"),
    )

    @Test
    fun builtinsSortByNameAndDropReservedScannedIds() {
        val scanned = listOf(entry("firefox", "Firefox"), entry("tawc:term", "Impostor"), entry("xterm", "XTerm"))
        assertEquals(
            listOf("tawc:add-entry", "firefox", "tawc:term", "tawc:update", "xterm"),
            LauncherEntry.withBuiltins(scanned, builtins).map { it.id },
        )
    }

    @Test
    fun addEntryStaysLastWhateverTheQuery() {
        val all = LauncherEntry.withBuiltins(listOf(entry("firefox", "Firefox"), entry("adder", "Adder")), builtins)
        assertEquals(
            listOf("adder", "firefox", "tawc:term", "tawc:update", "tawc:add-entry"),
            LauncherEntry.filter(all, emptySet(), showHidden = false, query = "").map { it.id },
        )
        // "add" prefixes both Adder and Add entry: Add entry still last.
        assertEquals(
            listOf("adder", "tawc:add-entry"),
            LauncherEntry.filter(all, emptySet(), showHidden = false, query = "add").map { it.id },
        )
        assertEquals(
            listOf("tawc:term"),
            LauncherEntry.filter(all, emptySet(), showHidden = false, query = "tawc t").map { it.id },
        )
    }

    @Test
    fun builtinsHideLikeAnyEntry() {
        val all = LauncherEntry.withBuiltins(listOf(entry("firefox", "Firefox")), builtins)
        assertEquals(
            listOf("firefox", "tawc:update", "tawc:add-entry"),
            LauncherEntry.filter(all, setOf("tawc:term"), showHidden = false, query = "").map { it.id },
        )
    }

    @Test
    fun builtinIdsRoundTrip() {
        for (b in LauncherEntry.Builtin.entries) assertEquals(b, LauncherEntry.Builtin.fromId(b.id))
        assertEquals(null, LauncherEntry.Builtin.fromId("firefox"))
    }
}
