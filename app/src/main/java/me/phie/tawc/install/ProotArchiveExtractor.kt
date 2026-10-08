package me.phie.tawc.install

import com.github.luben.zstd.ZstdInputStream
import me.phie.tawc.install.util.AndroidFsOps
import me.phie.tawc.install.util.FsOps
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/**
 * In-process tar extractor for every install method that extracts as
 * the app uid — tawcroot (the default) and proot. The name is
 * historical; only the chroot method stays out.
 *
 * The chroot path can lean on toybox `tar` running as root, where
 * directory mode bits in the archive are honoured but DAC is ignored
 * (root writes anywhere). Everyone else extracts as the app uid, where
 * DAC is real — and the Arch bootstrap contains directories whose
 * recorded mode is 0500 (`/etc/ca-certificates/extracted/cadir/` is
 * the canonical offender). When toybox sees a 0500 dir entry, it sets
 * the mode immediately, then the next regular-file entry inside that
 * dir fails with EPERM. Toybox has no `--no-same-permissions` flag, so
 * working around this in the shell is a non-starter.
 *
 * This runs in the JVM, outside any tawcroot process, so tawcroot's
 * lazy CAP_DAC_OVERRIDE emulation does not cover it — the deferral
 * below is still load-bearing. It is also what GNU tar does
 * (delayed directory permissions), so we are not inventing a policy.
 *
 * Doing the extract in pure Kotlin lets us:
 *   1. Defer applying the archived dir mode until the end (write
 *      everything in the dir first with mode 0700, then chmod the dir
 *      to its archived mode last).
 *   2. Skip ownership entirely — both methods lie about uid/gid at
 *      stat() time anyway, so the on-disk owner doesn't matter.
 *   3. Drop the FIFO/zstd-thread dance — we already use zstd-jni for
 *      decompression and commons-compress for tar parsing in other
 *      install paths; no shell, no toybox quirks.
 *
 * Hardlinks and symlinks go through [FsOps] (`link(2)` / `symlink(2)`).
 * Device nodes are skipped (Arch bootstrap doesn't ship any, and we
 * can't mknod as app uid anyway). Any unrecognised entry type is
 * skipped with a warning instead of failing the install.
 */
internal object ProotArchiveExtractor {

    /**
     * Extract [tarball] into [destDir]. Creates [destDir] if missing.
     * Optionally flattens a single top-level prefix (`stripPrefix` set
     * to `"root.x86_64"` for the Arch bootstrap that wraps everything
     * in that dir).
     *
     * @throws IOException on any extraction failure.
     */
    fun extract(
        tarball: File,
        destDir: String,
        stripPrefix: String?,
        onLine: (String) -> Unit,
    ) {
        openTarStream(tarball).use { tin ->
            extractStream(tin, destDir, stripPrefix, onLine)
        }
    }

    /**
     * Stream form of [extract] for callers that already hold a tar
     * stream (e.g. a `data.tar.xz` member inside a `.deb`, see
     * [me.phie.tawc.install.pkgbootstrap.DebExtractor]). Same
     * mode-deferral and path-containment behaviour; does not close
     * [tin].
     */
    internal fun extractStream(
        tin: TarArchiveInputStream,
        destDir: String,
        stripPrefix: String?,
        onLine: (String) -> Unit,
    ) = extractEntries({ tin.nextEntry }, tin, destDir, stripPrefix, onLine)

    /**
     * Core of [extractStream] with the entry source split out: [next]
     * yields entries (null = end) and [data] reads the current entry's
     * bytes. Lets [DistroImporter] interpose its allowlist and digest
     * on a stream that keeps every containment check here. [fs] is
     * injectable for JVM tests; [preserveMtime] restores file and dir
     * mtimes (symlinks keep the extract time). [roots], when set,
     * narrows containment from [destDir] to those top-level children
     * of it, so a symlink can't steer a write onto a sibling such as
     * the slot's `metadata.json`. [restoreXattrs] sets `user.*` xattrs
     * from PAX `SCHILY.xattr.*` headers, best-effort.
     */
    internal fun extractEntries(
        next: () -> TarArchiveEntry?,
        data: InputStream,
        destDir: String,
        stripPrefix: String?,
        onLine: (String) -> Unit,
        fs: FsOps = AndroidFsOps,
        preserveMtime: Boolean = false,
        roots: List<String>? = null,
        restoreXattrs: Boolean = false,
    ) {
        fun chmodSafe(path: File, mode: Int) = fs.chmod(path.absolutePath, mode)
        // Before the chmod: user.* xattrs need write permission.
        fun xattrs(entry: TarArchiveEntry, target: File) {
            if (!restoreXattrs) return
            for ((k, v) in entry.extraPaxHeaders) {
                val name = k.removePrefix("SCHILY.xattr.")
                if (name != k && name.startsWith("user.")) {
                    fs.setXattr(target.absolutePath, name, v.toByteArray(Charsets.UTF_8))
                }
            }
        }
        val dest = File(destDir).apply { mkdirs() }
        // Record (relative path, archived mode) of every dir we create
        // so we can re-apply the archive's mode at the end. Keyed by
        // depth-sorted iteration so deepest dirs get chmod'd first —
        // applying a 0500 mode to a parent before its children are
        // populated would re-create the original problem.
        val deferredDirModes = mutableListOf<Pair<File, Int>>()
        val deferredDirMtimes = mutableListOf<Pair<File, Long>>()

        // Resolve dest once so per-entry containment checks compare
        // against a normalised absolute path. We reject any entry whose
        // resolved target escapes [dest]; this is defence-in-depth
        // against a malicious bootstrap (both Arch tarballs are
        // PGP-verified, but the others rest on a single-origin SHA-256
        // — see notes/installation.md "Bootstrap integrity").
        val destReal = dest.canonicalFile
        val destPrefix = destReal.absolutePath + File.separator

        // Hoisted helper: compute a target File rooted in [dest],
        // throwing if [rel] escapes via `..` or absolute paths. The
        // canonical path of the eventual target must start with
        // destReal/.
        val rootPaths = roots?.map { File(destReal, it).absolutePath }
        fun resolveInside(rel: String, kind: String): File {
            val candidate = File(dest, rel).canonicalFile
            val abs = candidate.absolutePath
            val inside = if (rootPaths == null) {
                abs == destReal.absolutePath || abs.startsWith(destPrefix)
            } else {
                rootPaths.any { abs == it || abs.startsWith(it + File.separator) }
            }
            if (!inside) {
                throw IOException("tar $kind escapes rootfs: rel=\"$rel\" abs=\"$abs\"")
            }
            return candidate
        }

        run {
            while (true) {
                // Per-entry interrupt check: a multi-thousand-entry
                // bootstrap takes seconds to extract on a phone, and
                // the tar stream / FileOutputStream calls below don't
                // observe the thread interrupt themselves. Without
                // this gate a cancel during extract would only land
                // when the next stage starts. Clear+throw so the
                // caller's `catch (InterruptedIOException)` /
                // `runInterruptible` path takes over cleanly.
                if (Thread.interrupted()) {
                    throw InterruptedIOException("extract cancelled")
                }
                val entry: TarArchiveEntry = next() ?: break
                val rel = stripped(entry.name, stripPrefix) ?: continue
                if (rel.isEmpty() || rel == "/" || rel == ".") continue
                val target = resolveInside(rel, "entry")
                when {
                    entry.isDirectory -> {
                        target.mkdirs()
                        // 0700 while children are written; reapply the
                        // recorded mode at the end. Skipping mode 0
                        // entries (some tarballs leave dirs with 0
                        // mode as a marker — treat as 0755).
                        val mode = entry.mode and MODE_MASK
                        deferredDirModes += target to (if (mode == 0) MODE_0755 else mode)
                        if (preserveMtime) deferredDirMtimes += target to entry.modTime.time
                        chmodSafe(target, MODE_0700)
                        xattrs(entry, target)
                    }
                    entry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        target.delete()
                        // Note: we don't resolveInside(entry.linkName)
                        // — symlinks are *allowed* to point outside the
                        // rootfs in a fully-formed Linux install (e.g.
                        // /lib -> /usr/lib is fine; a relative
                        // ../something is fine). The traversal danger
                        // is *following* a symlink during a later
                        // file write, which we mitigate per-write
                        // (see entry.isFile below).
                        fs.symlink(entry.linkName, target.absolutePath)
                    }
                    entry.isLink -> {
                        // Hardlink. linkName is the in-archive path of
                        // the link target — both endpoints must stay
                        // inside the rootfs (a hardlink to outside
                        // would let us modify host files via the
                        // link).
                        //
                        // On most Android devices `untrusted_app`'s
                        // SELinux policy denies `link` on
                        // `app_data_file` (e.g. OnePlus's policy on
                        // Android 14: `avc: denied { link }` with
                        // `permissive=0`). When that happens we fall
                        // through to a relative symlink, which proot
                        // does at runtime anyway via `--link2symlink`.
                        // Behaviourally identical for the chroot's
                        // `getconf` programs and similar — the only
                        // observable difference is `stat().st_nlink`,
                        // which nothing in the chroot relies on.
                        target.parentFile?.mkdirs()
                        target.delete()
                        val linkRel = stripped(entry.linkName, stripPrefix)
                            ?: throw IOException("hardlink target outside archive: ${entry.linkName}")
                        val src = resolveInside(linkRel, "hardlink target")
                        if (!fs.link(src.absolutePath, target.absolutePath)) {
                            // EACCES / EPERM: SELinux denied `link` on
                            // `app_data_file`. Fall back to a relative
                            // symlink pointing at the same in-rootfs
                            // path. Same on-disk effect for the proot
                            // tracee since proot's `-r <rootfs>` keeps
                            // both endpoints inside the chroot view.
                            fs.symlink(relativizeSymlink(target, src), target.absolutePath)
                        }
                    }
                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        // Unlink any pre-existing entry at [target]
                        // before opening, as defence in depth. The
                        // earlier `resolveInside` already canonicalises
                        // the path (which follows existing symlinks),
                        // so a prior symlink at this path pointing
                        // outside would have been rejected at resolve
                        // time. The delete also guarantees we land on
                        // a fresh inode rather than truncating
                        // whatever a previous tar entry left behind.
                        target.delete()
                        FileOutputStream(target).use { out ->
                            data.copyTo(out)
                        }
                        xattrs(entry, target)
                        chmodSafe(target, entry.mode and MODE_MASK)
                        if (preserveMtime) target.setLastModified(entry.modTime.time)
                    }
                    else -> {
                        // Character/block devices, FIFOs, etc. — Arch
                        // bootstrap has none, and even if we could
                        // mknod as app uid we wouldn't want to. Log
                        // and skip rather than fail the install.
                        onLine("skip (unsupported type): $rel")
                    }
                }
            }
        }

        // Re-apply archived dir modes. Sort deepest-first so a 0500
        // parent never blocks chmod of a child it contains.
        deferredDirModes.sortedByDescending { it.first.absolutePath.count { c -> c == '/' } }
            .forEach { (dir, mode) -> chmodSafe(dir, mode) }
        // After the modes: a dir's mtime moves whenever a child lands,
        // so it can only be restored once nothing else will be written.
        // setLastModified works on 0500 dirs (owner-only check).
        for ((dir, mtime) in deferredDirMtimes) dir.setLastModified(mtime)

        onLine("extracted ${deferredDirModes.size} dirs into $destDir")
    }

    /** Open the tar stream, dispatching on the file extension. */
    private fun openTarStream(tarball: File): TarArchiveInputStream {
        val name = tarball.name.lowercase()
        val raw = BufferedInputStream(tarball.inputStream(), 256 * 1024)
        val decompressed: InputStream = when {
            name.endsWith(".tar.gz") || name.endsWith(".tgz") ->
                GzipCompressorInputStream(raw, /* decompressConcatenated = */ true)
            name.endsWith(".tar.zst") || name.endsWith(".tzst") ->
                ZstdInputStream(raw)
            name.endsWith(".tar.xz") || name.endsWith(".txz") ->
                XZInputStream(raw)
            name.endsWith(".tar") -> raw
            else -> throw IOException("Unsupported tarball extension: ${tarball.name}")
        }
        return TarArchiveInputStream(decompressed)
    }

    /**
     * Drop [stripPrefix] from [name] if non-null and matching. Returns
     * `null` if [name] is exactly the prefix (so callers can skip the
     * empty-relative-path entry), or if [name] is outside the prefix
     * (defensive — shouldn't happen on well-formed archives).
     */
    private fun stripped(name: String, stripPrefix: String?): String? {
        val cleaned = name.trimStart('/').removeSuffix("/")
        if (stripPrefix == null) return cleaned
        if (cleaned == stripPrefix) return null
        if (!cleaned.startsWith("$stripPrefix/")) return null
        return cleaned.substring(stripPrefix.length + 1)
    }

    /**
     * Compute a relative path from [link]'s parent directory to [target].
     * Used when we fall back from hardlink to symlink because SELinux
     * denied `link`. Stays relative so the chroot's view (where the
     * rootfs becomes `/`) resolves the same way as the on-disk path.
     */
    private fun relativizeSymlink(link: File, target: File): String {
        val linkDir = link.parentFile?.toPath() ?: return target.absolutePath
        return linkDir.relativize(target.toPath()).toString()
    }

}

// Kotlin has no octal literal syntax, so unix mode bits are spelled
// in decimal here (with the octal in a comment for readability).
private const val MODE_MASK = 4095   // 0o7777, the 12 mode bits
private const val MODE_0755 = 493    // rwxr-xr-x, sane default for dirs
private const val MODE_0700 = 448    // rwx------, scratch mode while we write children
