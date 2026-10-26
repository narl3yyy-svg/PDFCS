package com.narl3yyy.pdfcs.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.util.SizeF
import com.narl3yyy.pdfcs.camera.Look
import com.narl3yyy.pdfcs.library.ScanSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/** Renders pages with the platform PDF renderer. PdfRenderer allows one open page at a time. */
class PdfPages(file: File) : Closeable {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val lock = Mutex()
    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var closed = false

    val pageCount: Int = renderer.pageCount

    /** Page sizes in points, read once up front. */
    val sizes: List<SizeF> = List(pageCount) { i ->
        renderer.openPage(i).use { SizeF(it.width.toFloat(), it.height.toFloat()) }
    }

    fun cached(index: Int, width: Int): Bitmap? = cache.get("$index@$width")

    suspend fun render(index: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$index@$width"
        cache.get(key)?.let { return@withContext it }
        lock.withLock {
            if (closed || index !in 0 until pageCount) return@withLock null
            cache.get(key)?.let { return@withLock it }
            runCatching {
                renderer.openPage(index).use { page ->
                    val h = (width.toFloat() * page.height / page.width).toInt().coerceIn(1, width * 4)
                    Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        // Print mode: Android draws annotations (highlights, notes, ink) only when printing.
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        cache.put(key, it)
                    }
                }
            }.getOrNull()
        }
    }

    override fun close() {
        // Called from the UI thread when the viewer leaves; let a running render finish first.
        if (lock.tryLock()) {
            try { closeNow() } finally { lock.unlock() }
        } else {
            Thread {
                kotlinx.coroutines.runBlocking { lock.withLock { closeNow() } }
            }.start()
        }
    }

    private fun closeNow() {
        if (closed) return
        closed = true
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }

    companion object {
        /**
         * Renders every page of an existing (e.g. image-only) PDF into the scan
         * session so it can be OCR'd into a searchable copy.
         */
        suspend fun importIntoSession(
            file: File,
            session: ScanSession,
            onProgress: (Int, Int) -> Unit,
        ) = withContext(Dispatchers.IO) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    for (i in 0 until r.pageCount) {
                        coroutineContext.ensureActive()
                        r.openPage(i).use { page ->
                            // ~300 dpi for a typical page, capped for memory.
                            val scale = (3000f / max(page.width, page.height)).coerceAtMost(300f / 72f)
                            val bmp = Bitmap.createBitmap(
                                (page.width * scale).toInt().coerceAtLeast(1),
                                (page.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888,
                            )
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val out = session.newImageFile()
                            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                            bmp.recycle()
                            session.addPage(out, Look.ORIGINAL)
                        }
                        onProgress(i + 1, r.pageCount)
                    }
                }
            }
        }
    }
}
