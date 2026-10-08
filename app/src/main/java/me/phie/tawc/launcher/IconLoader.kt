package me.phie.tawc.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Async PNG icon loader for launcher cells.
 *
 * Backed by an in-memory `path → Bitmap` cache so the same icon shown on
 * different filter states (or the same row re-rendered after a filter
 * keystroke) doesn't decode the file twice. Decoding goes through
 * `BitmapFactory` with `inSampleSize` chosen to land at roughly the
 * target display size — a 256 × 256 PNG would otherwise cost ~256 KiB
 * of heap each, and a screen of ~50 of them adds up.
 *
 * The cache is byte-bounded (see [budgetBytes]): a full desktop distro
 * has hundreds of icon-bearing `.desktop` entries, so an unbounded map
 * would grow with the distro rather than with anything the app controls.
 *
 * Cached bitmaps remember their file's mtime + size; [dropStale] (run
 * on every rescan) evicts ones whose file changed, so an icon replaced
 * in place shows up without a process restart.
 *
 * Concurrency: each `load()` call sets `ImageView.tag` to the requested
 * path and re-checks it before applying the bitmap. So if the same
 * `ImageView` gets recycled with a different path mid-flight (rapid
 * filter typing), the stale completion is dropped.
 */
class IconLoader(
    private val scope: CoroutineScope,
    /** Target on-screen size in pixels. The decoded bitmap is no smaller
     *  than this, no more than 2× larger. */
    private val sizePx: Int,
    /** Cache ceiling in bytes of decoded bitmap. */
    budgetBytes: Int = budgetBytes(sizePx),
) {
    private class Cached(val bitmap: Bitmap, val stamp: Pair<Long, Long>)

    private val cache = object : LruCache<String, Cached>(budgetBytes) {
        override fun sizeOf(key: String, value: Cached) = value.bitmap.allocationByteCount
    }

    /**
     * [fallbackRes] is shown when [path] is empty or fails to decode,
     * so entries never render with a blank icon slot.
     */
    fun load(path: String, target: ImageView, fallbackRes: Int = 0) {
        if (path.isEmpty()) {
            applyFallback(target, fallbackRes)
            target.tag = null
            return
        }
        cache.get(path)?.let {
            target.setImageBitmap(it.bitmap)
            target.tag = path
            return
        }
        target.setImageDrawable(null)
        target.tag = path
        scope.launch {
            // Stamp before decoding: a change mid-decode is caught by
            // the next [dropStale].
            val (stamp, bmp) = withContext(Dispatchers.IO) { stamp(path) to decode(path, sizePx) }
            if (target.tag != path) return@launch
            if (bmp == null) {
                applyFallback(target, fallbackRes)
                return@launch
            }
            cache.put(path, Cached(bmp, stamp))
            target.setImageBitmap(bmp)
        }
    }

    /** Evict bitmaps whose file changed since decode. Stats every
     *  cached path, so call off the main thread. */
    fun dropStale() {
        for ((path, cached) in cache.snapshot()) {
            if (stamp(path) != cached.stamp) cache.remove(path)
        }
    }

    private fun stamp(path: String) = File(path).let { it.lastModified() to it.length() }

    private fun applyFallback(target: ImageView, fallbackRes: Int) {
        if (fallbackRes != 0) target.setImageResource(fallbackRes)
        else target.setImageDrawable(null)
    }

    companion object {
        /** Icons the cache holds even on a tiny heap — comfortably more
         *  than one screenful, so scrolling back up never re-decodes. */
        private const val MIN_CACHED_ICONS = 32

        /**
         * Cache ceiling for icons decoded at [sizePx]: an eighth of the
         * process heap, floored at [MIN_CACHED_ICONS] icons' worth.
         *
         * [heapBytes] defaults to the real heap limit (which honours
         * `largeHeap`, unlike `ActivityManager.memoryClass`); it is a
         * parameter only so tests can pin it.
         */
        internal fun budgetBytes(
            sizePx: Int,
            heapBytes: Long = Runtime.getRuntime().maxMemory(),
        ): Int {
            // decode() lands the shorter side in [sizePx, 2×sizePx), so
            // ~2× the target area at ARGB_8888 is a fair typical icon.
            val perIcon = 4L * sizePx * sizePx * 2
            val floor = MIN_CACHED_ICONS * perIcon
            return maxOf(heapBytes / 8, floor).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        /**
         * Decode [path] into a [Bitmap] no smaller than [targetPx] in its
         * shorter dimension. Returns null on any error (bad PNG, missing
         * file, decoder doesn't recognise the format, e.g. SVG handed
         * through by mistake). Internal: [EntryShortcuts] reuses the
         * bounded decode for pin icons.
         */
        internal fun decode(path: String, targetPx: Int): Bitmap? {
            val f = File(path)
            if (!f.isFile) return null
            return runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sample = 1
                val shorter = minOf(bounds.outWidth, bounds.outHeight)
                while (shorter / (sample * 2) >= targetPx) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeFile(path, opts)
            }.getOrNull()
        }
    }
}
