package me.phie.tawc.install

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Root's login shell, read out of the rootfs's own `/etc/passwd` so
 * `chsh -s /usr/bin/zsh` inside the rootfs is honoured by the in-app
 * terminal (wmww/tawc#7). The rootfs is app-uid-owned under tawcroot,
 * so this is a plain host-side file read — no rootfs entry needed.
 *
 * Falls back to [command] whenever the answer isn't a usable shell:
 * missing/unreadable passwd, no `root` line, empty shell field, or a
 * shell that doesn't resolve to an existing executable inside the
 * rootfs (`chsh` to a shell later uninstalled is the common case).
 * Only interactive terminal tabs use the result; every command spawn
 * stays on [command] (notes/terminal.md).
 */
internal object RootShell {
    const val DEFAULT = "/bin/bash"
    const val FALLBACK = "/bin/sh"

    /** Symlink hops before we call it a loop. */
    private const val MAX_HOPS = 40

    fun resolve(rootfs: File): String {
        val shell = passwdShell(rootfs) ?: return command(rootfs)
        return if (executable(rootfs, shell)) shell else command(rootfs)
    }

    /** The shell command spawns run (`-lc`): [DEFAULT] (bash), or
     *  [FALLBACK] in a rootfs without bash (custom imports, e.g.
     *  Alpine). Both take `-l` / `-c`. */
    fun command(rootfs: File): String =
        if (!executable(rootfs, DEFAULT) && executable(rootfs, FALLBACK)) FALLBACK else DEFAULT

    private fun executable(rootfs: File, path: String): Boolean {
        val target = resolveInRootfs(rootfs, path) ?: return false
        return target.isFile && target.canExecute()
    }

    /** Field 7 of the first `root` line of `<rootfs>/etc/passwd`, or
     *  null if there's no non-blank one. Matches on the name field
     *  only — a `roots` user, or `root` appearing in a GECOS/home
     *  field, must not match. */
    private fun passwdShell(rootfs: File): String? {
        val passwd = resolveInRootfs(rootfs, "/etc/passwd") ?: return null
        val lines = try {
            if (!passwd.isFile) return null
            passwd.readLines()
        } catch (e: IOException) {
            return null
        }
        for (line in lines) {
            val fields = line.split(':')
            if (fields.size < 7 || fields[0] != "root") continue
            return fields[6].trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    /**
     * Host-side [File] for an in-rootfs absolute path, following
     * symlinks *within the rootfs*: an absolute link target restarts
     * at the rootfs root rather than at the host's `/`, which
     * [File.getCanonicalFile] would do (and `/usr/bin/zsh ->
     * /usr/bin/zsh-5.9` is exactly that shape). Returns null on a
     * symlink loop; a path that simply doesn't exist comes back as a
     * non-existent File for the caller to test.
     */
    fun resolveInRootfs(rootfs: File, path: String): File? {
        val remaining = ArrayDeque(path.split('/').filter { it.isNotEmpty() })
        var resolved = ""
        var hops = 0
        while (remaining.isNotEmpty()) {
            val comp = remaining.removeFirst()
            if (comp == ".") continue
            if (comp == "..") {
                resolved = resolved.substringBeforeLast('/', "")
                continue
            }
            val next = "$resolved/$comp"
            val host = File(rootfs, next.removePrefix("/"))
            if (!Files.isSymbolicLink(host.toPath())) {
                resolved = next
                continue
            }
            if (++hops > MAX_HOPS) return null
            val target = try {
                Files.readSymbolicLink(host.toPath()).toString()
            } catch (e: IOException) {
                return null
            }
            // Absolute target: reinterpret against the rootfs root.
            // Relative: it's relative to the link's parent, which
            // `resolved` already names.
            if (target.startsWith("/")) resolved = ""
            for (c in target.split('/').filter { it.isNotEmpty() }.asReversed()) {
                remaining.addFirst(c)
            }
        }
        return File(rootfs, resolved.removePrefix("/"))
    }
}
