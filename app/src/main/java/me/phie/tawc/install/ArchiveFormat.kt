package me.phie.tawc.install

import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Tar stream behind an import archive, picked by magic bytes, not by
 * name (SAF display names are unreliable): zstd, gzip, xz, bzip2 or
 * plain tar (`ustar` at offset 257).
 */
internal object ArchiveFormat {
    enum class Kind { ZSTD, GZIP, XZ, BZIP2, TAR }

    fun sniff(head: ByteArray, n: Int): Kind? {
        fun at(off: Int, vararg b: Int) = n >= off + b.size && b.indices.all { head[off + it] == b[it].toByte() }
        return when {
            at(0, 0x28, 0xB5, 0x2F, 0xFD) -> Kind.ZSTD
            at(0, 0x1F, 0x8B) -> Kind.GZIP
            at(0, 0xFD, '7'.code, 'z'.code, 'X'.code, 'Z'.code, 0) -> Kind.XZ
            at(0, 'B'.code, 'Z'.code, 'h'.code) -> Kind.BZIP2
            at(257, 'u'.code, 's'.code, 't'.code, 'a'.code, 'r'.code) -> Kind.TAR
            else -> null
        }
    }

    /** The decompressed tar bytes of [input]. Throws [IOException] for
     *  anything that isn't one of [Kind]. */
    fun open(input: InputStream): InputStream {
        val buf = input as? BufferedInputStream ?: BufferedInputStream(input, 1 shl 20)
        val head = ByteArray(HEAD)
        buf.mark(HEAD)
        var n = 0
        while (n < HEAD) {
            val r = buf.read(head, n, HEAD - n)
            if (r < 0) break
            n += r
        }
        buf.reset()
        return when (sniff(head, n)) {
            Kind.ZSTD -> ZstdInputStream(buf)
            Kind.GZIP -> GzipCompressorInputStream(buf, /* decompressConcatenated = */ true)
            Kind.XZ -> XZInputStream(buf)
            Kind.BZIP2 -> BZip2CompressorInputStream(buf, /* decompressConcatenated = */ true)
            Kind.TAR -> buf
            null -> throw IOException("not a tar archive (or tar.zst/.gz/.xz/.bz2)")
        }
    }

    private const val HEAD = 512
}
