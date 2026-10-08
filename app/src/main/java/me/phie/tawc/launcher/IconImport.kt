package me.phie.tawc.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import me.phie.tawc.install.Installation
import me.phie.tawc.install.util.atomicWriteBytes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Copies an image the user picked into a rootfs as a TAWC-owned icon,
 * for the editor's Load button. Imports go into hicolor under the
 * guest's user icon base, named `tawc-<slug>`: a plain theme name, so
 * guest desktops reading the same `.desktop` file find it too, and the
 * prefix keeps clear of anything a package installs. Nothing deletes
 * imports; they're small.
 *
 * Rasters are re-encoded as PNG (the resolver only returns PNGs),
 * scaled down to fit [RASTER_PX]; SVGs are copied as-is.
 */
internal object IconImport {

    const val PREFIX = "tawc-"

    /** hicolor under the user icon base (`$HOME` is `/root`). */
    private const val HICOLOR_SUBDIR = "root/.local/share/icons/hicolor"

    /** Raster imports are scaled to fit this square, never up. A
     *  smaller image still goes in the `256x256` dir: hicolor tolerates
     *  that, and our resolver goes by directory order, not size. */
    const val RASTER_PX = 256

    /** Same cap as `icon_cache::MAX_SOURCE_BYTES`: a bigger SVG would
     *  never render. */
    const val MAX_SVG_BYTES = 1024 * 1024

    enum class Kind(val ext: String, val subdir: String) {
        PNG("png", "256x256/apps"),
        SVG("svg", "scalable/apps"),
    }

    fun dir(rootfs: File, kind: Kind) = File(rootfs, "$HICOLOR_SUBDIR/${kind.subdir}")

    /** Slug for a document named [displayName], extension dropped;
     *  `icon` when nothing slugifiable is left. */
    fun slugFor(displayName: String?): String {
        val base = displayName.orEmpty().let { n ->
            val dot = n.lastIndexOf('.')
            if (dot > 0) n.substring(0, dot) else n
        }
        return Installation.slugifyLabel(base) ?: "icon"
    }

    /**
     * Icon name for [bytes] of [kind]: `tawc-<slug>`, then `-2`, `-3`, …
     * The first candidate whose [kind] file holds identical bytes is
     * reused; otherwise the first with neither extension taken wins.
     * [existing] returns a candidate file's bytes, or null if absent.
     */
    fun chooseName(
        slug: String,
        kind: Kind,
        bytes: ByteArray,
        existing: (name: String, kind: Kind) -> ByteArray?,
    ): String {
        var n = 1
        while (true) {
            val name = if (n == 1) "$PREFIX$slug" else "$PREFIX$slug-$n"
            val same = existing(name, kind)
            if (same != null && same.contentEquals(bytes)) return name
            if (same == null && Kind.entries.all { it == kind || existing(name, it) == null }) return name
            n++
        }
    }

    /** Is this document an SVG, going by its MIME type or name? */
    fun isSvg(mime: String?, displayName: String?): Boolean =
        mime == "image/svg+xml" || displayName.orEmpty().endsWith(".svg", ignoreCase = true)

    /**
     * Import [uri] into [rootfs] and return the icon name. Blocking I/O;
     * call on Dispatchers.IO. Throws [IOException] on anything that
     * didn't produce an icon (unreadable, undecodable, oversized SVG).
     */
    fun import(context: Context, uri: Uri, rootfs: File): String {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        val kind = if (isSvg(resolver.getType(uri), displayName)) Kind.SVG else Kind.PNG
        val bytes = when (kind) {
            Kind.SVG -> readSvg(context, uri)
            Kind.PNG -> encodePng(decodeScaled(context, uri))
        }
        val name = chooseName(slugFor(displayName), kind, bytes) { name, k ->
            File(dir(rootfs, k), "$name.${k.ext}").takeIf { it.isFile }?.readBytes()
        }
        val target = File(dir(rootfs, kind), "$name.${kind.ext}")
        if (!target.isFile) {
            target.parentFile?.mkdirs()
            atomicWriteBytes(target, bytes)
        }
        return name
    }

    private fun readSvg(context: Context, uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("can't open")
        val bytes = input.use { it.readNBytesCompat(MAX_SVG_BYTES + 1) }
        if (bytes.size > MAX_SVG_BYTES) throw IOException("SVG over 1 MiB")
        return bytes
    }

    private fun decodeScaled(context: Context, uri: Uri): Bitmap = try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = fitWithin(info.size.width, info.size.height, RASTER_PX)
            decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Exception) {
        // ImageDecoder throws DecodeException (an IOException) but also
        // IllegalArgumentException etc. on odd inputs.
        throw e as? IOException ?: IOException(e.message, e)
    }

    private fun encodePng(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("PNG encode failed")
        return out.toByteArray()
    }

    /** [w]×[h] scaled to fit a [max] square, aspect kept, never up. */
    fun fitWithin(w: Int, h: Int, max: Int): Pair<Int, Int> {
        if (w <= max && h <= max) return w to h
        return if (w >= h) max to maxOf(1, h * max / w) else maxOf(1, w * max / h) to max
    }

    /** Up to [limit] bytes; `InputStream.readNBytes` is API 33. */
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
