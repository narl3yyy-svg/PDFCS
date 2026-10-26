package com.narl3yyy.pdfcs.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class PdfDoc(
    val file: File,
    val title: String,
    val pages: Int,
    val sizeBytes: Long,
    val modified: Long,
)

/** The user's PDFs, stored privately in the app's files directory. */
class DocumentRepository(private val context: Context) {
    val dir: File = File(context.filesDir, "library").apply { mkdirs() }
    private val thumbDir = File(context.cacheDir, "thumbs")
    private val pageCounts = ConcurrentHashMap<String, Int>()
    private val _docs = MutableStateFlow<List<PdfDoc>>(emptyList())
    val docs: StateFlow<List<PdfDoc>> = _docs.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".pdf", ignoreCase = true) }.orEmpty()
        _docs.value = files
            .map { f -> PdfDoc(f, f.nameWithoutExtension, pageCount(f), f.length(), f.lastModified()) }
            .sortedByDescending { it.modified }
    }

    fun pageCount(file: File): Int = pageCounts.getOrPut("${file.path}:${file.lastModified()}") {
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { it.pageCount }
            }
        }.getOrDefault(0)
    }

    fun uniqueFile(title: String): File {
        val base = sanitize(title).ifBlank { "Scan" }
        var f = File(dir, "$base.pdf")
        var n = 2
        while (f.exists()) f = File(dir, "$base ($n).pdf").also { n++ }
        return f
    }

    suspend fun rename(doc: PdfDoc, title: String): File? = withContext(Dispatchers.IO) {
        val target = uniqueFile(title)
        if (doc.file.renameTo(target)) target.also { refresh() } else null
    }

    suspend fun delete(docs: Collection<PdfDoc>) = withContext(Dispatchers.IO) {
        docs.forEach { it.file.delete() }
        refresh()
    }

    /** Copies a PDF from another app (Files, e-mail, browser…) into the library. */
    suspend fun import(uri: Uri): File = withContext(Dispatchers.IO) {
        val name = displayName(uri)?.removeSuffix(".pdf")?.removeSuffix(".PDF") ?: "Imported"
        val target = uniqueFile(name)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open the selected file" }
            target.outputStream().use { input.copyTo(it) }
        }
        if (pageCount(target) <= 0) {
            target.delete()
            error("That file is not a readable PDF")
        }
        refresh()
        target
    }

    suspend fun exportTo(doc: PdfDoc, uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "w").use { out ->
            requireNotNull(out) { "Cannot write to the selected location" }
            doc.file.inputStream().use { it.copyTo(out) }
        }
    }

    suspend fun merge(docs: List<PdfDoc>, title: String): File = withContext(Dispatchers.IO) {
        val target = uniqueFile(title)
        val tmp = File(dir, ".${target.name}.part")
        try {
            PDFMergerUtility().apply {
                docs.forEach { addSource(it.file) }
                destinationFileName = tmp.path
                mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
            }
            check(tmp.renameTo(target)) { "Could not save the merged PDF" }
        } finally {
            tmp.delete()
        }
        refresh()
        target
    }

    /** First-page thumbnail, cached on disk. */
    suspend fun thumbnail(file: File, width: Int = 360): Bitmap? = withContext(Dispatchers.IO) {
        thumbDir.mkdirs()
        val cached = File(thumbDir, "${file.name.hashCode()}_${file.lastModified()}_$width.png")
        if (cached.exists()) BitmapFactory.decodeFile(cached.path)?.let { return@withContext it }
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    r.openPage(0).use { page ->
                        val h = (width.toFloat() * page.height / page.width).toInt().coerceIn(1, width * 3)
                        Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also { bmp ->
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            cached.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        }
                    }
                }
            }
        }.getOrNull()
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    companion object {
        fun sanitize(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(120)
    }
}
