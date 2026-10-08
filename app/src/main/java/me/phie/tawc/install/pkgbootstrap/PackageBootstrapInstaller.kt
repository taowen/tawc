package me.phie.tawc.install.pkgbootstrap

import android.content.Context
import me.phie.tawc.R
import me.phie.tawc.install.BootstrapCache
import me.phie.tawc.install.Downloader
import me.phie.tawc.install.InstallProgress
import me.phie.tawc.install.InstallStage
import me.phie.tawc.install.InstallationMethod
import me.phie.tawc.install.InstallationStore
import me.phie.tawc.install.MirrorProxy
import me.phie.tawc.install.ProotArchiveExtractor
import me.phie.tawc.install.SignatureVerifier
import me.phie.tawc.install.TawcrootMethod
import me.phie.tawc.install.distro.Distro
import me.phie.tawc.install.distro.PackageBootstrap
import me.phie.tawc.install.util.HumanSize
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * Packages bootstrap flavor, Debian family: assemble a rootfs from
 * signed repo metadata by running **real debootstrap on-device**,
 * replacing the tarball path's download/verify/extract stages. The
 * flavor-agnostic tail (`configure → TawcInstaller →
 * initPackageManager → installBasePackages`) runs unchanged after
 * this returns. Design + trust analysis: notes/installation.md
 * "Bootstrap flavors".
 *
 * Division of labour (do not blur it):
 *  - **Kotlin owns trust, transport, environment, progress.** The
 *    clearsigned `InRelease` is verified against the shipped keyring
 *    ([PackageBootstrap.keyResource]) before anything downloaded is
 *    believed; the package index is fetched by hash out of the
 *    verified body; every deb (including the busybox/perl the
 *    workspace itself runs) is SHA-256-checked against that index
 *    before any downloaded code executes.
 *  - **debootstrap owns all dpkg/apt logic** — dependency resolution
 *    (`--print-debs`), unpack ordering, the dpkg database, merged-usr,
 *    maintainer-script choreography. Nothing here parses a Depends
 *    line or fabricates dpkg state.
 *
 * debootstrap runs as a *workspace guest*: `bootstrap-work/` is laid
 * out like a tiny rootfs (busybox + perl-base + a `file://` mirror of
 * verified files) and entered through the same
 * [InstallationMethod.runInside] every in-rootfs install step already
 * uses; stage 2 then runs inside the freshly-built real rootfs.
 *
 * debootstrap is invoked with `--no-check-sig`. **That flag does not
 * weaken anything**: signature verification already happened *in
 * Kotlin, upstream of debootstrap* — the `InRelease` in the local
 * mirror was clearsign-verified against the APK-shipped Debian archive
 * keyring, the index was hash-verified against that InRelease, and
 * every pool file was hash-verified against the index. debootstrap
 * only ever reads this pre-verified local mirror; there is no network
 * fetch it could skip a check on. See notes/installation.md
 * "Bootstrap integrity" hard rules before touching this.
 */
internal class PackageBootstrapInstaller(
    private val context: Context,
    private val store: InstallationStore,
    private val cache: BootstrapCache,
    private val distro: Distro,
    private val method: InstallationMethod,
    private val id: String,
    private val bootstrap: PackageBootstrap,
    private val mirrorProxy: MirrorProxy?,
) {

    /**
     * Run the flavor's replacement for stages 1-3. On return,
     * `store.rootfsDir(id)` holds a complete, dpkg-configured base
     * system. Throws on any failure; the caller parks the install in
     * FAILED and the workspace is reaped by uninstall.
     */
    fun install(
        checkCancel: () -> Unit,
        progress: (InstallProgress) -> Unit,
        log: (String) -> Unit,
    ) {
        // The workspace-guest trick is only exercised under tawcroot
        // (the release method). The service gate already rejects other
        // methods; this is defence in depth for direct callers.
        if (method.key != TawcrootMethod.KEY) {
            throw IOException(
                "packages bootstrap flavor requires the tawcroot method (got '${method.key}')",
            )
        }
        val work = store.bootstrapWorkDir(id)
        if (work.exists()) work.deleteRecursively()

        // One clean re-resolve on pool rotation: sid republishes
        // several times a day, so a deb can 404 between index fetch
        // and download. by-hash makes the *index* fetch race-free; a
        // pool 404 means our index generation rotated out — re-run the
        // whole trust+resolve phase from a fresh InRelease exactly
        // once (mirroring the tarball path's single evict-and-retry).
        // Never mix files from two index generations: the mirror dir
        // is rebuilt from the new index on retry.
        var attempt = 0
        while (true) {
            checkCancel()
            try {
                resolveAndDownload(work, attempt, checkCancel, progress, log)
                break
            } catch (e: PoolRotatedException) {
                if (attempt >= 1) {
                    throw IOException(
                        "pool file vanished again after a fresh re-resolve (${e.message}); " +
                            "the mirror is misbehaving — retry the install later",
                    )
                }
                log("pool: ${e.message} — archive rotated mid-install; re-resolving once from a fresh InRelease")
                attempt++
            }
        }

        checkCancel()
        runStage1(work, log, progress)

        checkCancel()
        // Move the bootstrap target into its final home before stage 2
        // so the second stage runs against the real rootfs path (and a
        // post-stage-2 failure of later pipeline stages leaves a
        // debuggable tree in the standard place). Failure cleanup is
        // exact: any throw parks the install FAILED and uninstall's
        // RootfsCleaner wipes rootfs/ and bootstrap-work/ alike.
        val rootfsDir = store.rootfsDir(id)
        if (!File(work, "rootfs").renameTo(rootfsDir)) {
            throw IOException("failed to move ${work.resolve("rootfs")} to $rootfsDir")
        }

        runStage2(rootfsDir, log, progress)

        // Bootstrap complete — drop the workspace (busybox, mirror,
        // debootstrap copy). The pool cache under BootstrapCache
        // persists for reinstall reuse; sweepStale owns its TTL.
        log("workspace: removing ${work.absolutePath}")
        if (!work.deleteRecursively()) {
            log("workspace: warning — could not fully remove $work")
        }
    }

    // ---- trust phase + workspace + resolve + downloads ---------------

    private class PoolRotatedException(message: String) : IOException(message)

    private fun resolveAndDownload(
        work: File,
        attempt: Int,
        checkCancel: () -> Unit,
        progress: (InstallProgress) -> Unit,
        log: (String) -> Unit,
    ) {
        val arch = bootstrap.packagesArch
        val mirrorDir = File(work, "mirror")
        // Rebuild the mirror from scratch per attempt so no file from
        // a previous index generation survives.
        if (mirrorDir.exists()) mirrorDir.deleteRecursively()
        val suiteDir = File(mirrorDir, "dists/${bootstrap.suite}").apply { mkdirs() }

        // --- InRelease: fetch, verify clearsign, enforce freshness ---
        progress(InstallProgress(
            InstallStage.DOWNLOADING,
            context.getString(R.string.install_progress_downloading_index),
        ))
        val inReleaseUrl = "${bootstrap.archiveRoot}/dists/${bootstrap.suite}/InRelease"
        val inReleaseFile = File(suiteDir, "InRelease")
        log("index: fetch $inReleaseUrl" + if (attempt > 0) " (re-resolve)" else "")
        Downloader.download(mirrorProxy?.wrap(inReleaseUrl) ?: inReleaseUrl, inReleaseFile)

        checkCancel()
        progress(InstallProgress(
            InstallStage.VERIFYING,
            context.getString(R.string.install_progress_verifying_index),
        ))
        val keys = SignatureVerifier.loadKeyRing(context, bootstrap.keyResource)
        val body = Clearsign.verify(inReleaseFile.readBytes(), keys, "InRelease")
        val release = DebianRelease.parse(String(body, Charsets.UTF_8))
        release.requireFresh(Instant.now(), VALID_UNTIL_SKEW)
        log("index: InRelease verified against res/raw/${bootstrap.keyResource} " +
            "(date=${release.date}, valid-until=${release.validUntil})")

        // --- Packages.xz: fetch by hash, verify digest, parse --------
        val indexRel = "main/binary-$arch/Packages.xz"
        val entry = release.fileEntry(indexRel)
        // sid republishes several times a day; the by-hash path is the
        // only race-free way to fetch the exact index the verified
        // InRelease describes.
        val indexUrl = if (release.acquireByHash) {
            "${bootstrap.archiveRoot}/dists/${bootstrap.suite}/main/binary-$arch/by-hash/SHA256/${entry.sha256Hex}"
        } else {
            "${bootstrap.archiveRoot}/dists/${bootstrap.suite}/$indexRel"
        }
        val indexFile = File(suiteDir, indexRel)
        indexFile.parentFile!!.mkdirs()
        log("index: fetch $indexUrl (${HumanSize.format(entry.sizeBytes)})")
        Downloader.download(mirrorProxy?.wrap(indexUrl) ?: indexUrl, indexFile) { read, _ ->
            progress(InstallProgress(
                InstallStage.DOWNLOADING,
                context.getString(R.string.install_progress_downloading_index),
                ((read * 100) / entry.sizeBytes).toInt().coerceIn(0, 100),
            ))
        }
        verifySha256(indexFile, entry.sha256Hex, "Packages.xz")

        checkCancel()
        val index = indexFile.inputStream().use { fin ->
            XZInputStream(fin).use { xin -> PackagesIndex.parse(xin) }
        }
        log("index: ${index.size} packages in ${bootstrap.suite}/main $arch")

        // --- workspace guest (busybox + perl from the verified index) —
        buildWorkspace(work, index, checkCancel, log)

        // --- resolve the package set with debootstrap --print-debs ---
        checkCancel()
        progress(InstallProgress(
            InstallStage.DOWNLOADING,
            context.getString(R.string.install_progress_resolving_packages),
        ))
        val names = printDebs(work, log)
        log("resolve: ${names.size} packages: ${names.joinToString(" ")}")

        // --- download + verify every deb into the local mirror pool --
        val pkgs = names.map { n ->
            index[n] ?: throw IOException(
                "debootstrap resolved '$n' but the verified index has no such package — " +
                    "index/debootstrap disagreement, refusing to continue",
            )
        }
        val totalBytes = pkgs.sumOf { it.sizeBytes }
        var doneBytes = 0L
        for ((i, pkg) in pkgs.withIndex()) {
            checkCancel()
            val pooled = downloadPooled(pkg) { readInFile ->
                val agg = doneBytes + readInFile
                progress(InstallProgress(
                    InstallStage.DOWNLOADING,
                    context.getString(
                        R.string.install_progress_downloading_packages,
                        i + 1, pkgs.size,
                        HumanSize.format(agg), HumanSize.format(totalBytes),
                    ),
                    ((agg * 100) / totalBytes).toInt().coerceIn(0, 100),
                ))
            }
            val dest = File(mirrorDir, pkg.filename)
            dest.parentFile!!.mkdirs()
            pooled.copyTo(dest, overwrite = true)
            doneBytes += pkg.sizeBytes
        }
        log("pool: ${pkgs.size} debs (${HumanSize.format(totalBytes)}) verified into local mirror")
    }

    /**
     * Lay out `bootstrap-work/` as a minimal rootfs the method layer
     * can enter: merged-usr skeleton, busybox (static) plus the
     * libc6/libcrypt1/perl-base debootstrap's `pkgdetails_perl` needs
     * — all extracted in Kotlin from index-verified debs — the exact
     * paths [TawcrootMethod.startInside]'s spawn prefix hardcodes
     * (`/usr/bin/env`, `/bin/bash`, `/bin/sh`), and the vendored
     * debootstrap tree from the APK asset.
     */
    private fun buildWorkspace(
        work: File,
        index: Map<String, PackagesIndex.Pkg>,
        checkCancel: () -> Unit,
        log: (String) -> Unit,
    ) {
        val usrBin = File(work, "usr/bin").apply { mkdirs() }
        File(work, "usr/lib").mkdirs()
        File(work, "usr/sbin").mkdirs()
        File(work, "tmp").mkdirs()
        File(work, "root").mkdirs()
        // Merged-usr skeleton. lib64 covers the x86_64 dynamic loader
        // path (/lib64/ld-linux-x86-64.so.2); harmless on arm64.
        for ((link, target) in listOf("bin" to "usr/bin", "sbin" to "usr/sbin",
                "lib" to "usr/lib", "lib64" to "usr/lib")) {
            val f = File(work, link)
            if (!f.exists()) android.system.Os.symlink(target, f.absolutePath)
        }

        // Workspace packages, from the same verified index as
        // everything else. busybox-static provides sh/ar/tar/xz/wget/
        // chroot/sha256sum for debootstrap; perl-base (+ its libc6/
        // libcrypt1 deps) provides the perl that debootstrap's
        // pkgdetails implementation requires (there is no shell
        // fallback in upstream debootstrap).
        // No per-file progress here — four small debs, seconds of work
        // between the index stage and the resolve stage; the log lines
        // are enough. The three library debs are also in the resolved
        // base set and reach the mirror through the main download loop.
        for (name in WORKSPACE_PACKAGES) {
            checkCancel()
            val pkg = index[name] ?: throw IOException(
                "workspace package '$name' not in the verified index — archive layout changed?",
            )
            log("workspace: ${pkg.filename} (${HumanSize.format(pkg.sizeBytes)})")
            val pooled = downloadPooled(pkg) { }
            DebExtractor.extractDataTar(pooled, work) { line -> log("workspace: $line") }
        }

        val busybox = File(usrBin, "busybox")
        if (!busybox.isFile) {
            throw IOException("busybox-static did not provide usr/bin/busybox")
        }
        android.system.Os.chmod(busybox.absolutePath, 493 /* 0755 */)

        // /bin/sh → busybox (ash). Needed before the first guest spawn:
        // the shims below are shebang scripts.
        val sh = File(usrBin, "sh")
        if (!sh.exists()) android.system.Os.symlink("busybox", sh.absolutePath)

        // /bin/bash shim: the spawn prefix runs `/bin/bash -lc <cmd>`,
        // Debian's busybox has no `bash` applet name, and busybox ash
        // accepts -l/-c fine — so alias it. debootstrap itself is
        // POSIX sh and runs under ash.
        writeScript(File(usrBin, "bash"), "#!/bin/sh\nexec /bin/busybox sh \"\$@\"\n")

        // /usr/bin/env shim: the spawn prefix is `/usr/bin/env -i
        // KEY=VAL… prog…` (tawcroot no longer passes GNU's -C, which
        // busybox env lacks; the shim still takes it). Handle exactly
        // those flags, then delegate.
        writeScript(File(usrBin, "env"), """
            #!/bin/sh
            # GNU-env shim over busybox env for the bootstrap workspace:
            # supports -i and -C <dir>, which is all the app's spawn
            # prefix uses (busybox env has no -C).
            clear=
            while [ $# -gt 0 ]; do
              case "$1" in
                -i) clear=-i; shift ;;
                -C) cd "$2" || exit 125; shift 2 ;;
                *) break ;;
              esac
            done
            exec /bin/busybox env ${'$'}clear "${'$'}@"
        """.trimIndent() + "\n")

        // Vendored debootstrap tree from the APK asset (packed by the
        // packDebootstrap Gradle task; pin in deps/deps.list).
        val debootstrapDir = File(work, "debootstrap")
        if (!debootstrapDir.exists()) {
            val tmpTar = File(work, "debootstrap.tar")
            context.assets.open(DEBOOTSTRAP_ASSET).use { a ->
                tmpTar.outputStream().use { out -> a.copyTo(out) }
            }
            ProotArchiveExtractor.extract(tmpTar, debootstrapDir.absolutePath, null) { }
            tmpTar.delete()
            log("workspace: debootstrap tree at /debootstrap")
        }
    }

    /**
     * Applet-farm preamble for every workspace-guest command: symlink
     * each busybox applet into /usr/bin (skipping paths that already
     * exist, so the sh/bash/env shims are never clobbered). debootstrap
     * expects ar/tar/xzcat/wget/sha256sum/… as plain PATH commands.
     */
    private val appletSetup = """
        set -e
        for a in $(/bin/busybox --list); do
          [ -e "/usr/bin/${'$'}a" ] || /bin/busybox ln -sf busybox "/usr/bin/${'$'}a"
        done
    """.trimIndent()

    private fun debootstrapArgs(): String =
        "--arch=${bootstrap.packagesArch} --variant=minbase --no-check-sig " +
            "--include=debian-archive-keyring --extractor=ar"

    /** Run debootstrap `--print-debs` in the workspace guest; returns package names. */
    private fun printDebs(work: File, log: (String) -> Unit): List<String> {
        // stdout goes to a file (runInside merges stdout+stderr into
        // one stream, which would interleave W:/I: chatter with the
        // package list); Kotlin reads it host-side afterwards.
        // --keep-debootstrap-dir skips kill_target's `rm -rf
        // --one-file-system`, which busybox rm can't parse; the scratch
        // target is deleted host-side below.
        val cmd = appletSetup + "\n" +
            "export DEBOOTSTRAP_DIR=/debootstrap\n" +
            "sh /debootstrap/debootstrap ${debootstrapArgs()} --keep-debootstrap-dir " +
            "--print-debs ${bootstrap.suite} /print-debs-target file:///mirror >/print-debs.out\n"
        val r = method.runInside(work.absolutePath, cmd, onLine = filteredLog(log))
        if (!r.ok) {
            throw IOException(
                "debootstrap --print-debs failed (exit=${r.exitCode})\n" +
                    debootstrapLogTail(File(work, "print-debs-target")),
            )
        }
        val out = File(work, "print-debs.out")
        val names = (if (out.isFile) out.readText() else "")
            .split(Regex("\\s+")).filter { it.isNotBlank() }
        out.delete()
        File(work, "print-debs-target").deleteRecursively()
        if (names.isEmpty()) {
            throw IOException("debootstrap --print-debs produced an empty package list")
        }
        return names
    }

    private fun runStage1(work: File, log: (String) -> Unit, progress: (InstallProgress) -> Unit) {
        progress(InstallProgress(
            InstallStage.EXTRACTING,
            context.getString(R.string.install_progress_extracting_packages),
        ))
        val cmd = appletSetup + "\n" +
            "export DEBOOTSTRAP_DIR=/debootstrap\n" +
            "sh /debootstrap/debootstrap ${debootstrapArgs()} --foreign " +
            "${bootstrap.suite} /rootfs file:///mirror\n"
        val r = method.runInside(work.absolutePath, cmd, onLine = filteredLog(log))
        if (!r.ok) {
            throw IOException(
                "debootstrap first stage failed (exit=${r.exitCode})\n" +
                    debootstrapLogTail(File(work, "rootfs")),
            )
        }
    }

    private fun runStage2(rootfsDir: File, log: (String) -> Unit, progress: (InstallProgress) -> Unit) {
        progress(InstallProgress(
            InstallStage.EXTRACTING,
            context.getString(R.string.install_progress_second_stage),
        ))
        // Inside the *real* rootfs now, using the freshly-unpacked
        // dpkg/bash/coreutils; /proc comes from the per-spawn binds,
        // same as every initPackageManager run. debootstrap removes
        // /debootstrap from the target itself on success.
        val r = method.runInside(
            rootfsDir.absolutePath,
            "/debootstrap/debootstrap --second-stage",
            onLine = filteredLog(log),
        )
        if (!r.ok) {
            throw IOException(
                "debootstrap second stage failed (exit=${r.exitCode})\n" +
                    debootstrapLogTail(rootfsDir),
            )
        }
    }

    /** Tail of `<target>/debootstrap/debootstrap.log`, for error detail. */
    private fun debootstrapLogTail(target: File): String {
        val f = File(target, "debootstrap/debootstrap.log")
        if (!f.isFile) return "(no debootstrap.log)"
        val lines = f.readLines()
        return "debootstrap.log tail:\n" + lines.takeLast(20).joinToString("\n")
    }

    private fun filteredLog(log: (String) -> Unit): (String) -> Unit = { line ->
        // debootstrap prints one I: line per package for validate/
        // retrieve/extract — keep those (they're the only progress
        // signal for the long stages) but drop wget-style noise.
        if (line.isNotBlank()) log("debootstrap: $line")
    }

    // ---- downloads ---------------------------------------------------

    /**
     * Fetch [pkg] into the content-addressed pool
     * (`bootstrap-pkgs-<cacheKey>/<sha256>.deb`), verifying the digest
     * against the index entry before the file is considered present.
     * A cached file re-verifies (cheap at these sizes) so a corrupt
     * leftover can never be reused. A 404 raises
     * [PoolRotatedException] for the single-re-resolve loop.
     */
    private fun downloadPooled(
        pkg: PackagesIndex.Pkg,
        onProgress: (Long) -> Unit,
    ): File {
        val pool = cache.pkgPoolDir(distro.cacheKey)
        val dest = File(pool, "${pkg.sha256Hex}.deb")
        if (dest.isFile && dest.length() == pkg.sizeBytes && sha256Of(dest) == pkg.sha256Hex) {
            onProgress(pkg.sizeBytes)
            return dest
        }
        dest.delete()
        val url = "${bootstrap.archiveRoot}/${pkg.filename}"
        try {
            Downloader.download(mirrorProxy?.wrap(url) ?: url, dest) { read, _ -> onProgress(read) }
        } catch (e: IOException) {
            if (e !is InterruptedIOException && e.message?.contains("HTTP 404") == true) {
                throw PoolRotatedException("${pkg.filename}: ${e.message}")
            }
            throw e
        }
        verifySha256(dest, pkg.sha256Hex, pkg.filename)
        return dest
    }

    private fun verifySha256(file: File, expectedHex: String, label: String) {
        val actual = sha256Of(file)
        if (actual != expectedHex) {
            file.delete()
            throw IOException(
                "SHA-256 mismatch for $label: expected $expectedHex, got $actual. " +
                    "File is corrupt or tampered with.",
            )
        }
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                if (Thread.interrupted()) throw InterruptedIOException("verify cancelled")
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeScript(file: File, content: String) {
        file.writeText(content)
        android.system.Os.chmod(file.absolutePath, 493 /* 0755 */)
    }

    companion object {
        /** APK asset holding the vendored debootstrap tree. */
        private const val DEBOOTSTRAP_ASSET = "debootstrap/debootstrap.tar"

        /**
         * Packages extracted into the workspace itself (from the
         * verified index, like everything else). busybox-static is the
         * toolbox; perl-base + its two library deps exist because
         * upstream debootstrap's pkgdetails has exactly two
         * implementations — perl or a compiled C binary — and no shell
         * fallback. All but busybox-static are in the resolved base
         * set anyway, so the extra download cost is one ~1 MB deb.
         */
        private val WORKSPACE_PACKAGES = listOf("busybox-static", "libc6", "libcrypt1", "perl-base")

        /**
         * Clock allowance for `Valid-Until` (sid publishes with a
         * 7-day window). Small and constant on purpose — do not make
         * it configurable; that would be a rollback-protection
         * downgrade knob.
         */
        private val VALID_UNTIL_SKEW: Duration = Duration.ofHours(3)
    }
}
