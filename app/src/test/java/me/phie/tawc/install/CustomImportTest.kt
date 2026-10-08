package me.phie.tawc.install

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files

/**
 * Custom-distro import ([DistroImporter.scan], [DistroImporter.Reader]
 * loose path, [ArchiveLayout], [RootfsFacts], [ArchiveFormat]) on
 * synthetic tarballs. notes/installation.md "Custom distros".
 */
class CustomImportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val host = "arm64-v8a"

    // ---- archive builder ---------------------------------------------

    private class Tar(out: OutputStream) {
        val t = TarArchiveOutputStream(out).apply {
            setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
        }

        fun dir(name: String) = apply {
            t.putArchiveEntry(TarArchiveEntry(if (name.endsWith("/")) name else "$name/"))
            t.closeArchiveEntry()
        }

        fun file(name: String, bytes: ByteArray, mode: Int = 420) = apply {
            t.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong(); this.mode = mode })
            t.write(bytes)
            t.closeArchiveEntry()
        }

        fun file(name: String, text: String) = file(name, text.toByteArray())

        fun symlink(name: String, target: String) = apply {
            t.putArchiveEntry(TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply { linkName = target })
            t.closeArchiveEntry()
        }

        fun hardlink(name: String, target: String) = apply {
            t.putArchiveEntry(TarArchiveEntry(name, TarConstants.LF_LINK).apply { linkName = target })
            t.closeArchiveEntry()
        }
    }

    private fun tar(build: Tar.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        Tar(out).apply(build).t.close()
        return out.toByteArray()
    }

    private fun elf(machine: Int) = ByteArray(64).apply {
        this[0] = 0x7F; this[1] = 'E'.code.toByte(); this[2] = 'L'.code.toByte(); this[3] = 'F'.code.toByte()
        this[4] = 2; this[5] = 1
        this[18] = (machine and 0xFF).toByte(); this[19] = (machine shr 8).toByte()
    }

    /** An Alpine-shaped (musl, BusyBox, no bash) tree under [p]. */
    private fun Tar.alpine(p: String, machine: Int = 183) {
        if (p.isNotEmpty()) dir(p)
        dir("${p}bin")
        file("${p}bin/busybox", elf(machine), mode = 493)
        symlink("${p}bin/sh", "/bin/busybox")
        dir("${p}etc")
        file("${p}etc/os-release", "NAME=\"Alpine Linux\"\nID=alpine\nPRETTY_NAME=\"Alpine Linux v3.20\"\n")
        file("${p}etc/passwd", "root:x:0:0:root:/root:/bin/sh\n")
        dir("${p}lib")
        file("${p}lib/ld-musl-aarch64.so.1", elf(machine), mode = 493)
        dir("${p}usr")
        dir("${p}usr/bin")
        symlink("${p}usr/bin/env", "/bin/busybox")
    }

    /** A Debian-shaped (glibc, merged /usr, bash) tree under [p]. */
    private fun Tar.debian(p: String) {
        if (p.isNotEmpty()) dir(p)
        symlink("${p}bin", "usr/bin")
        dir("${p}etc")
        symlink("${p}etc/os-release", "../usr/lib/os-release")
        symlink("${p}etc/resolv.conf", "../run/systemd/resolve/stub-resolv.conf")
        dir("${p}usr")
        dir("${p}usr/bin")
        file("${p}usr/bin/bash", elf(183), mode = 493)
        hardlink("${p}usr/bin/rbash", "${p}usr/bin/bash")
        file("${p}usr/bin/env", elf(183), mode = 493)
        symlink("${p}usr/bin/sh", "dash")
        dir("${p}usr/lib")
        file("${p}usr/lib/os-release", "PRETTY_NAME=\"Debian GNU/Linux trixie/sid\"\nNAME='Debian GNU/Linux'\nID=debian\n")
        dir("${p}usr/lib/aarch64-linux-gnu")
        file("${p}usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", elf(183), mode = 493)
    }

    private fun scan(bytes: ByteArray) = DistroImporter.scan(ByteArrayInputStream(bytes), host)

    private fun refused(bytes: ByteArray, want: String) {
        try {
            scan(bytes)
            fail("accepted; wanted '$want'")
        } catch (e: IOException) {
            assertTrue("${e.message} lacks '$want'", e.message!!.contains(want))
        }
    }

    // ---- classification ----------------------------------------------

    @Test
    fun plainTopLevelRootfs() {
        val s = scan(tar { alpine("") })
        assertEquals(DistroImporter.Kind.PLAIN_ROOTFS, s.kind)
        assertEquals("", s.layout!!.root)
        val f = s.facts!!
        assertEquals("Alpine Linux v3.20", f.osName)
        assertEquals("alpine", f.osId)
        assertEquals("arm64-v8a", f.abi)
        assertEquals(RootfsFacts.LIBC_MUSL, f.libc)
        assertFalse(f.hasBash)
        assertTrue(f.hasSh && f.hasEnv)
        assertTrue(s.entries > 5)
    }

    @Test
    fun dotSlashPrefixedRootfs() {
        val s = scan(tar { dir("./"); debian("./") })
        assertEquals(DistroImporter.Kind.PLAIN_ROOTFS, s.kind)
        assertEquals("", s.layout!!.root)
        assertEquals("Debian GNU/Linux trixie/sid", s.facts!!.osName)
        assertEquals(RootfsFacts.LIBC_GLIBC, s.facts!!.libc)
        assertTrue(s.facts!!.hasBash)
    }

    @Test
    fun wrapperDirRootfs() {
        val s = scan(tar { alpine("root.aarch64/") })
        assertEquals("root.aarch64/", s.layout!!.root)
        assertFalse(s.layout!!.tawc)
        assertEquals("Alpine Linux v3.20", s.facts!!.osName)
    }

    @Test
    fun repackedExportIsDamaged() {
        val meta = Installation(
            id = "src", label = "Mine", distro = "arch", arch = host, method = "tawcroot",
            installedAtMillis = 1L, sourceUrl = "x", andoEnabled = true,
        )
        val s = scan(tar {
            dir("./")
            debian("./rootfs/")
            dir("./tawcroot")
            file("./tawcroot/version", "1\n")
            file("./metadata.json", meta.toJson())
            file("./tawc-export.json", "{\"format\": 1}")
        })
        assertEquals(DistroImporter.Kind.DAMAGED_EXPORT, s.kind)
        assertEquals("rootfs/", s.layout!!.root)
        assertTrue(s.layout!!.tawc)
        assertEquals(meta, s.metadata)
        assertTrue(s.problems.toString(), s.problems.any { it.contains("bad export manifest") })
        assertTrue(s.problems.toString(), s.problems.any { it.contains("end marker") })
    }

    @Test
    fun badManifestFirstIsDamaged() {
        val s = scan(tar {
            file(DistroArchive.MANIFEST, "{not json")
            file(DistroArchive.METADATA, "{}")
            debian("rootfs/")
        })
        assertEquals(DistroImporter.Kind.DAMAGED_EXPORT, s.kind)
        assertNull(s.metadata)
        assertTrue(s.problems.toString(), s.problems.any { it.contains("bad export manifest") })
        assertTrue(s.problems.toString(), s.problems.any { it.contains("default settings") })
    }

    @Test
    fun containerImageIsRefused() = refused(
        tar {
            file("manifest.json", "[]")
            file("repositories", "{}")
            dir("0123abcd")
            file("0123abcd/layer.tar", "x")
        },
        "docker export",
    )

    @Test
    fun noRootfsIsRefused() = refused(tar { dir("docs"); file("docs/readme", "hi") }, "no Linux root filesystem")

    @Test
    fun wrongArchIsRefused() = refused(tar { alpine("", machine = 62) }, "built for x86_64")

    @Test
    fun nothingToRunIsRefused() = refused(
        tar { dir("etc"); dir("usr"); dir("usr/bin"); file("usr/bin/tool", elf(183)) },
        "/usr/bin/env",
    )

    @Test
    fun notATarIsRefused() = refused("hello world, not an archive".toByteArray(), "not a tar archive")

    @Test
    fun compressedFormatsBySniffing() {
        val plain = tar { alpine("") }
        val wraps: List<(OutputStream) -> OutputStream> = listOf(
            { GzipCompressorOutputStream(it) },
            { BZip2CompressorOutputStream(it) },
            { XZOutputStream(it, LZMA2Options()) },
        )
        for (wrap in wraps) {
            val out = ByteArrayOutputStream()
            wrap(out).use { it.write(plain) }
            assertEquals("Alpine Linux v3.20", scan(out.toByteArray()).facts!!.osName)
        }
    }

    // ---- extraction ----------------------------------------------------

    /** The import job's loose path with a tiny lookahead, so held,
     *  pending and streamed entries all get exercised. */
    private fun importLoose(bytes: ByteArray, maxEntries: Int, holdBytes: Long): Pair<File, ArchiveLayout> {
        val r = DistroImporter.Reader(ByteArrayInputStream(bytes), holdBytes = holdBytes)
        assertNull(r.openExport())
        r.lookahead(maxEntries)
        val layout = r.classify(host).layout!!
        val dest = tmp.newFolder()
        r.extractLoose(dest, layout, {}, {}, NioFsOps)
        return dest to layout
    }

    @Test
    fun wrapperDirIsStrippedAndOutsideSkipped() {
        val bytes = tar {
            file("README", "outside")
            debian("debroot/")
            file("debroot/etc/hostname", ByteArray(200) { 'h'.code.toByte() })
            file("elsewhere/x", "outside")
        }
        for ((entries, hold) in listOf(3 to (1L shl 20), 4096 to (1L shl 20), 4096 to 100L)) {
            val (dest, layout) = importLoose(bytes, entries, hold)
            assertEquals("debroot/", layout.root)
            val root = File(dest, "rootfs")
            assertEquals("usr/bin", Files.readSymbolicLink(File(root, "bin").toPath()).toString())
            assertEquals(200, File(root, "etc/hostname").length())
            assertTrue(File(root, "usr/bin/rbash").exists())
            assertTrue(File(root, "usr/lib/os-release").readText().contains("trixie"))
            assertFalse(File(dest, "README").exists())
            assertFalse(File(dest, "elsewhere").exists())
            assertFalse(File(root, "README").exists())
        }
    }

    @Test
    fun lateEtcFallsBackToTopLevelGuess() {
        // ./usr first and long, etc/ beyond the lookahead: the guess
        // from top-level names must still land on the right root.
        val bytes = tar {
            dir("./")
            dir("./usr")
            dir("./usr/bin")
            for (i in 0 until 50) file("./usr/bin/t$i", elf(183))
            file("./usr/bin/env", elf(183))
            file("./usr/bin/bash", elf(183))
            dir("./etc")
            file("./etc/hostname", "box\n")
        }
        val (dest, layout) = importLoose(bytes, 10, 1L shl 20)
        assertEquals("", layout.root)
        assertEquals("box\n", File(dest, "rootfs/etc/hostname").readText())
        assertTrue(File(dest, "rootfs/usr/bin/t49").exists())
        val facts = RootfsFacts.probe(File(dest, "rootfs"))
        assertEquals("arm64-v8a", facts.abi)
        assertTrue(facts.hasBash && facts.hasEnv)
    }

    @Test
    fun repackedExportRestoresStoreAndMetadata() {
        val meta = Installation(
            id = "src", label = "Mine", distro = "arch", arch = host, method = "tawcroot",
            installedAtMillis = 1L, sourceUrl = "x",
        )
        val bytes = tar {
            dir("./")
            debian("./rootfs/")
            symlink("./rootfs/usr/lib/libx.so", "tawcroot:link:42")
            dir("./tawcroot")
            file("./tawcroot/version", "1\n")
            dir("./tawcroot/link")
            file("./tawcroot/link/42", "shared\n")
            file("./metadata.json", meta.toJson())
        }
        val r = DistroImporter.Reader(ByteArrayInputStream(bytes))
        assertNull(r.openExport())
        r.lookahead(3)
        val layout = r.classify(host).layout!!
        val dest = tmp.newFolder()
        r.extractLoose(dest, layout, {}, {}, NioFsOps)
        assertEquals("shared\n", File(dest, "tawcroot/link/42").readText())
        assertTrue(File(dest, "rootfs/usr/bin/bash").exists())
        assertFalse(File(dest, "metadata.json").exists())
        assertEquals(meta, r.metadataFor(layout))
    }

    @Test
    fun hostileNamesAreStillRejected() {
        val outside = tmp.newFolder("outside")
        val cases: List<Tar.() -> Unit> = listOf(
            { alpine(""); file("etc/../../escape", "x") },
            { alpine(""); symlink("evil", outside.path); file("evil/x", "x") },
            { alpine(""); hardlink("h", "../../x") },
        )
        for ((i, case) in cases.withIndex()) {
            try {
                importLoose(tar(case), 4096, 1L shl 20)
                fail("case $i was accepted")
            } catch (_: IOException) {
            }
            assertFalse("case $i wrote outside", File(outside, "x").exists())
        }
    }

    // ---- records and setup ---------------------------------------------

    private val target = DistroImporter.Target(
        id = "alp", label = null, hostAbi = host, sourceName = "alpine.tar.gz",
        nowMillis = 9L, appVersionCode = 7L, allFilesDeclared = true,
    )
    private val alpineFacts = RootfsFacts("Alpine Linux v3.20", "alpine", host, RootfsFacts.LIBC_MUSL, false, true, true)

    @Test
    fun customRecord() {
        val r = DistroImporter.looseRecord(target, null, null, alpineFacts) { _, _ -> true }
        assertEquals(Installation.DISTRO_CUSTOM, r.distro)
        assertEquals("Alpine Linux v3.20", r.label)
        assertEquals(TawcrootMethod.KEY, r.method)
        assertEquals(host, r.arch)
        assertEquals("alpine.tar.gz", r.sourceUrl)
        assertEquals(Installation.FLAVOR_IMPORTED, r.bootstrapFlavor)
        assertEquals(7L, r.installedAtAppVersionCode)
        assertEquals(9L, r.importedAtMillis)
        assertEquals("alpine", r.osId)
        assertEquals(RootfsFacts.LIBC_MUSL, r.libc)
        assertEquals(Installation.State.INSTALLING, r.state)
        assertEquals(r, Installation.fromJson(r.toJson()))
    }

    @Test
    fun damagedRecordKeepsKnownDistroElseCustom() {
        val meta = Installation(
            id = "src", label = "Mine", distro = "arch", arch = "x86_64", method = "tawcroot",
            installedAtMillis = 1L, sourceUrl = "x", andoEnabled = true,
        )
        val known = DistroImporter.looseRecord(target, meta, null, alpineFacts) { _, _ -> true }
        assertEquals("arch", known.distro)
        assertEquals("Mine", known.label)
        assertEquals(host, known.arch)
        assertTrue(known.andoEnabled)
        val unknown = DistroImporter.looseRecord(target, meta.copy(distro = "fedora"), null, alpineFacts) { _, _ -> false }
        assertEquals(Installation.DISTRO_CUSTOM, unknown.distro)
        assertTrue(unknown.andoEnabled)
        assertEquals("Alpine Linux v3.20", unknown.osName)
        // Not tawcroot: settings don't carry.
        val proot = DistroImporter.looseRecord(target, meta.copy(method = "proot"), null, alpineFacts) { _, _ -> true }
        assertEquals(Installation.DISTRO_CUSTOM, proot.distro)
        assertFalse(proot.andoEnabled)
    }

    @Test
    fun setupCustomFillsGaps() {
        val root = tmp.newFolder("rfs")
        File(root, "etc").mkdirs()
        Files.createSymbolicLink(File(root, "etc/resolv.conf").toPath(), File("../run/systemd/stub").toPath())
        DistroImporter.setupCustom(root, alpineFacts.copy(hasBash = true), {}, NioFsOps)
        assertEquals("nameserver 8.8.8.8\n", File(root, "etc/resolv.conf").readText())
        assertFalse(Files.isSymbolicLink(File(root, "etc/resolv.conf").toPath()))
        assertEquals(1023, NioFsOps.lstat(File(root, "tmp").path).perm)
        assertEquals(448, NioFsOps.lstat(File(root, "root").path).perm)
        assertEquals(ShellDefaults.BASHRC_STUB, File(root, "root/.bashrc").readText())

        // Idempotent, and leaves a working resolv.conf / user files alone.
        File(root, "etc/resolv.conf").writeText("nameserver 1.1.1.1\n")
        File(root, "root/.bashrc").writeText("mine\n")
        DistroImporter.setupCustom(root, alpineFacts.copy(hasBash = true), {}, NioFsOps)
        assertEquals("nameserver 1.1.1.1\n", File(root, "etc/resolv.conf").readText())
        assertEquals("mine\n", File(root, "root/.bashrc").readText())

        val noBash = tmp.newFolder("rfs2")
        DistroImporter.setupCustom(noBash, alpineFacts, {}, NioFsOps)
        assertFalse(File(noBash, "root/.bashrc").exists())
        assertTrue(File(noBash, "etc/resolv.conf").isFile)
    }

    // ---- small parsers -------------------------------------------------

    @Test
    fun elfAndOsRelease() {
        assertEquals("arm64-v8a", RootfsFacts.elfAbi(elf(183)))
        assertEquals("x86_64", RootfsFacts.elfAbi(elf(62)))
        assertEquals("e_machine 999", RootfsFacts.elfAbi(elf(999)))
        assertNull(RootfsFacts.elfAbi("#!/bin/sh\necho not elf at all".toByteArray()))
        val big = elf(0).apply { this[5] = 2; this[18] = 0; this[19] = 183.toByte() }
        assertEquals("arm64-v8a", RootfsFacts.elfAbi(big))

        val os = RootfsFacts.parseOsRelease("# c\nNAME=\"A \\\"B\\\"\"\nID=x\nVERSION_ID='1.0'\nBAD\n")
        assertEquals("A \"B\"", os["NAME"])
        assertEquals("x", os["ID"])
        assertEquals("1.0", os["VERSION_ID"])
    }

    @Test
    fun layoutMapping() {
        val plain = ArchiveLayout("w/")
        assertEquals(ArchiveLayout.Mapped.Target("rootfs"), plain.map("w"))
        assertEquals(ArchiveLayout.Mapped.Target("rootfs/etc/x"), plain.map("w/etc/x"))
        assertEquals(ArchiveLayout.Mapped.Outside, plain.map("other"))
        val tawc = ArchiveLayout("e/rootfs/")
        assertEquals(ArchiveLayout.Mapped.Target("rootfs/etc"), tawc.map("e/rootfs/etc"))
        assertEquals(ArchiveLayout.Mapped.Target("tawcroot/version"), tawc.map("e/tawcroot/version"))
        assertEquals(ArchiveLayout.Mapped.Metadata, tawc.map("e/metadata.json"))
        assertEquals(ArchiveLayout.Mapped.Skip, tawc.map("e/tawc-export-end.json"))
        assertEquals(ArchiveLayout.Mapped.Outside, tawc.map("e/ando/sock"))
        assertEquals("a/b", ArchiveLayout.normalize("./a/b/"))
        assertEquals("a", ArchiveLayout.normalize("/./a"))
        assertEquals("", ArchiveLayout.normalize("./"))
    }
}
