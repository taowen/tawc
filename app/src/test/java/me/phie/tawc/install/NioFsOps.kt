package me.phie.tawc.install

import me.phie.tawc.install.util.FsOps
import me.phie.tawc.install.util.FsStat
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime

/** [FsOps] over the JDK's `unix:` attribute view, for JVM tests
 *  (android.system.Os is a stub there). Linux hosts only. */
internal object NioFsOps : FsOps {
    private fun p(path: String): Path = Paths.get(path)

    override fun lstat(path: String): FsStat {
        val a = try {
            Files.readAttributes(p(path), "unix:mode,ino,dev,nlink,size,lastModifiedTime", LinkOption.NOFOLLOW_LINKS)
        } catch (e: Exception) {
            throw IOException("lstat $path: $e", e)
        }
        return FsStat(
            mode = a["mode"] as Int,
            ino = a["ino"] as Long,
            dev = a["dev"] as Long,
            nlink = (a["nlink"] as Int).toLong(),
            size = a["size"] as Long,
            mtimeMs = (a["lastModifiedTime"] as FileTime).toMillis(),
        )
    }

    override fun readlink(path: String): String = Files.readSymbolicLink(p(path)).toString()

    override fun symlink(target: String, path: String) {
        Files.createSymbolicLink(p(path), p(target))
    }

    override fun link(src: String, dst: String): Boolean {
        Files.createLink(p(dst), p(src))
        return true
    }

    override fun userXattrs(path: String): List<Pair<String, ByteArray>> = try {
        val v = Files.getFileAttributeView(p(path), java.nio.file.attribute.UserDefinedFileAttributeView::class.java)
        v.list().map { name ->
            val buf = java.nio.ByteBuffer.allocate(v.size(name))
            v.read(name, buf)
            "user.$name" to buf.array()
        }
    } catch (_: Exception) {
        emptyList()
    }

    override fun setXattr(path: String, name: String, value: ByteArray) {
        try {
            Files.getFileAttributeView(p(path), java.nio.file.attribute.UserDefinedFileAttributeView::class.java)
                .write(name.removePrefix("user."), java.nio.ByteBuffer.wrap(value))
        } catch (_: Exception) {
        }
    }

    override fun chmod(path: String, mode: Int) {
        Files.setAttribute(p(path), "unix:mode", mode)
    }
}
