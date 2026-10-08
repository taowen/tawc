package me.phie.tawc.install

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * [RootShell.resolve] against synthetic rootfs trees: the passwd
 * lookup, the name-field-only match, and every fallback-to-bash rule
 * (missing file/line/field, missing or non-executable binary,
 * symlinks that resolve outside or in a loop).
 */
class RootShellTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val rootfs: File by lazy { tmp.newFolder("rootfs") }

    /** Create an executable file at an in-rootfs absolute path. */
    private fun shellBin(path: String): File =
        File(rootfs, path.removePrefix("/")).apply {
            parentFile!!.mkdirs()
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }

    private fun passwd(vararg lines: String) {
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/passwd").writeText(lines.joinToString("\n") + "\n")
    }

    private fun link(at: String, target: String) {
        val f = File(rootfs, at.removePrefix("/"))
        f.parentFile!!.mkdirs()
        Files.createSymbolicLink(f.toPath(), File(target).toPath())
    }

    private fun resolve(): String = RootShell.resolve(rootfs)

    @Test
    fun bashByDefault() {
        shellBin("/bin/bash")
        passwd("root:x:0:0:root:/root:/bin/bash")
        assertEquals("/bin/bash", resolve())
    }

    @Test
    fun noBashFallsBackToSh() {
        // Alpine-style: /bin/sh -> busybox, no bash.
        shellBin("/bin/busybox")
        link("/bin/sh", "/bin/busybox")
        passwd("root:x:0:0:root:/root:/bin/bash")
        assertEquals("/bin/sh", resolve())
        assertEquals("/bin/sh", RootShell.command(rootfs))
        shellBin("/bin/bash")
        assertEquals("/bin/bash", RootShell.command(rootfs))
    }

    @Test
    fun customShellHonoured() {
        shellBin("/usr/bin/zsh")
        passwd(
            "root:x:0:0:root:/root:/usr/bin/zsh",
            "daemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin",
        )
        assertEquals("/usr/bin/zsh", resolve())
    }

    @Test
    fun absoluteSymlinkResolvedInsideRootfs() {
        shellBin("/usr/bin/zsh-5.9")
        // An absolute target must be read against the rootfs root, not
        // the host's /usr/bin (where a host zsh-5.9 might well exist).
        link("/usr/bin/zsh", "/usr/bin/zsh-5.9")
        passwd("root:x:0:0:root:/root:/usr/bin/zsh")
        assertEquals("/usr/bin/zsh", resolve())
    }

    @Test
    fun symlinkToMissingTargetFallsBack() {
        link("/usr/bin/zsh", "/usr/bin/zsh-5.9")
        passwd("root:x:0:0:root:/root:/usr/bin/zsh")
        assertEquals("/bin/bash", resolve())
    }

    @Test
    fun symlinkLoopFallsBack() {
        link("/usr/bin/zsh", "/usr/bin/zsh2")
        link("/usr/bin/zsh2", "/usr/bin/zsh")
        passwd("root:x:0:0:root:/root:/usr/bin/zsh")
        assertEquals("/bin/bash", resolve())
    }

    @Test
    fun missingBinaryFallsBack() {
        passwd("root:x:0:0:root:/root:/usr/bin/fish")
        assertEquals("/bin/bash", resolve())
    }

    @Test
    fun nonExecutableFallsBack() {
        shellBin("/usr/bin/zsh").setExecutable(false)
        passwd("root:x:0:0:root:/root:/usr/bin/zsh")
        assertEquals("/bin/bash", resolve())
    }

    @Test
    fun directoryFallsBack() {
        File(rootfs, "usr/bin/zsh").mkdirs()
        passwd("root:x:0:0:root:/root:/usr/bin/zsh")
        assertEquals("/bin/bash", resolve())
    }

    @Test
    fun missingPasswdFallsBack() {
        shellBin("/usr/bin/zsh")
        assertEquals("/bin/bash", resolve())
    }

    @Test
    fun noRootLineFallsBack() {
        shellBin("/usr/bin/zsh")
        passwd("daemon:x:1:1:daemon:/usr/sbin:/usr/bin/zsh")
        assertEquals("/bin/bash", resolve())
    }

    @Test
    fun blankShellFieldFallsBack() {
        passwd("root:x:0:0:root:/root:")
        assertEquals("/bin/bash", resolve())
    }

    @Test
    fun malformedPasswdFallsBack() {
        shellBin("/usr/bin/zsh")
        passwd("", "root", "root:x:0:0", "# comment")
        assertEquals("/bin/bash", resolve())
    }

    @Test
    fun onlyTheNameFieldMatchesRoot() {
        shellBin("/usr/bin/zsh")
        shellBin("/usr/bin/ksh")
        passwd(
            // `root` in the GECOS and home fields, and a `roots` user:
            // none of these is root's line.
            "roots:x:1000:1000:root:/root:/usr/bin/zsh",
            "notroot:x:1001:1001:the root user:/home/root:/usr/bin/ksh",
        )
        assertEquals("/bin/bash", resolve())
    }

    @Test
    fun firstRootLineWins() {
        shellBin("/usr/bin/zsh")
        shellBin("/usr/bin/ksh")
        passwd(
            "root:x:0:0:root:/root:/usr/bin/zsh",
            "root:x:0:0:root:/root:/usr/bin/ksh",
        )
        assertEquals("/usr/bin/zsh", resolve())
    }

    @Test
    fun passwdReachedThroughSymlinkedEtc() {
        // Debian-style merged trees symlink whole dirs; /etc itself is
        // real there, but the resolver must handle a linked component
        // anywhere in the path.
        shellBin("/usr/bin/zsh")
        File(rootfs, "real-etc").mkdirs()
        File(rootfs, "real-etc/passwd").writeText("root:x:0:0:root:/root:/usr/bin/zsh\n")
        link("/etc", "/real-etc")
        assertEquals("/usr/bin/zsh", resolve())
    }
}
