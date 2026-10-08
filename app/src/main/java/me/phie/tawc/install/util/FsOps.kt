package me.phie.tawc.install.util

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/**
 * The handful of POSIX file operations the distro archive code
 * ([me.phie.tawc.install.DistroExporter], [me.phie.tawc.install.ProotArchiveExtractor])
 * needs beyond java.io. Injected so plain-JVM unit tests (where
 * `android.system.Os` is a stub) can run the same code against a real
 * temp dir; production uses [AndroidFsOps].
 */
internal interface FsOps {
    /** `lstat(2)`; throws [IOException] on failure. */
    fun lstat(path: String): FsStat

    fun readlink(path: String): String

    fun symlink(target: String, path: String)

    /** `link(2)`. Returns false when the kernel/SELinux denies hard
     *  links (EACCES/EPERM) so the caller can fall back; throws on
     *  anything else. */
    fun link(src: String, dst: String): Boolean

    /** chmod including setuid/setgid/sticky bits. Best-effort. */
    fun chmod(path: String, mode: Int)

    /** `user.*` xattrs of [path] (full names). Best-effort: empty on
     *  any error (unreadable file, fs without xattrs). */
    fun userXattrs(path: String): List<Pair<String, ByteArray>>

    /** Best-effort `setxattr`. */
    fun setXattr(path: String, name: String, value: ByteArray)
}

/** The `lstat` fields the archive code reads. [mode] includes the
 *  `S_IFMT` type bits. */
internal data class FsStat(
    val mode: Int,
    val ino: Long,
    val dev: Long,
    val nlink: Long,
    val size: Long,
    val mtimeMs: Long,
) {
    val type: Int get() = mode and S_IFMT
    val isDir: Boolean get() = type == S_IFDIR
    val isFile: Boolean get() = type == S_IFREG
    val isSymlink: Boolean get() = type == S_IFLNK
    val perm: Int get() = mode and PERM_MASK

    companion object {
        // Literal values: OsConstants are stubbed to 0 in JVM tests.
        const val S_IFMT = 0xF000      // 0o170000
        const val S_IFDIR = 0x4000     // 0o040000
        const val S_IFREG = 0x8000     // 0o100000
        const val S_IFLNK = 0xA000     // 0o120000
        const val PERM_MASK = 0xFFF    // 0o7777
    }
}

internal object AndroidFsOps : FsOps {
    override fun lstat(path: String): FsStat = try {
        val s = Os.lstat(path)
        FsStat(s.st_mode, s.st_ino, s.st_dev, s.st_nlink, s.st_size, s.st_mtime * 1000L)
    } catch (e: ErrnoException) {
        throw IOException("lstat $path: ${e.message}", e)
    }

    override fun readlink(path: String): String = try {
        Os.readlink(path)
    } catch (e: ErrnoException) {
        throw IOException("readlink $path: ${e.message}", e)
    }

    override fun symlink(target: String, path: String) = try {
        Os.symlink(target, path)
    } catch (e: ErrnoException) {
        throw IOException("symlink $path -> $target: ${e.message}", e)
    }

    override fun link(src: String, dst: String): Boolean = try {
        Os.link(src, dst)
        true
    } catch (e: ErrnoException) {
        if (e.errno == OsConstants.EACCES || e.errno == OsConstants.EPERM) false
        else throw IOException("link $dst -> $src: ${e.message}", e)
    }

    override fun chmod(path: String, mode: Int) {
        try {
            Os.chmod(path, mode)
        } catch (_: ErrnoException) {
        }
    }

    override fun userXattrs(path: String): List<Pair<String, ByteArray>> = try {
        Os.listxattr(path).filter { it.startsWith("user.") }.map { it to Os.getxattr(path, it) }
    } catch (_: ErrnoException) {
        emptyList()
    }

    override fun setXattr(path: String, name: String, value: ByteArray) {
        try {
            Os.setxattr(path, name, value, 0)
        } catch (_: ErrnoException) {
        }
    }
}

/** True if [f] exists without following a final symlink. */
internal fun FsOps.lexists(f: File): Boolean =
    try { lstat(f.path); true } catch (_: IOException) { false }
