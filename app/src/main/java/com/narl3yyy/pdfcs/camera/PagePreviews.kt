package com.narl3yyy.pdfcs.camera

import android.graphics.Bitmap
import android.util.LruCache
import com.narl3yyy.pdfcs.library.ScanPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** In-memory cache of rendered page previews for the review grid and editor. */
object PagePreviews {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    // Full-size photos are big; never decode more than two at once.
    private val gate = Semaphore(2)

    fun cached(page: ScanPage, maxSide: Int): Bitmap? = cache.get("${page.renderKey}@$maxSide")

    suspend fun load(dir: File, page: ScanPage, maxSide: Int): Bitmap? {
        val key = "${page.renderKey}@$maxSide"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.Default) {
            gate.withPermit {
                runCatching { PageImage.render(File(dir, page.source), page, maxSide) }
                    .getOrNull()?.also { cache.put(key, it) }
            }
        }
    }

    /** The unprocessed photo, for the crop editor. */
    suspend fun loadSource(file: File, maxSide: Int): Bitmap? {
        val key = "src:${file.path}@$maxSide"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.Default) {
            gate.withPermit { runCatching { PageImage.decode(file, maxSide) }.getOrNull()?.also { cache.put(key, it) } }
        }
    }
}
