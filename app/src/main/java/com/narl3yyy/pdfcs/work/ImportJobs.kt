package com.narl3yyy.pdfcs.work

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.narl3yyy.pdfcs.AppGraph
import com.narl3yyy.pdfcs.camera.Look
import com.narl3yyy.pdfcs.camera.PageImage
import com.narl3yyy.pdfcs.library.ScanPage
import com.narl3yyy.pdfcs.library.ScanSession
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * Imports that turn into searchable documents by themselves: a scanned PDF or a set of photos is
 * prepared as a job (pages found, straightened at render time) and queued for "Digital text"
 * conversion with the user's default settings. The finished PDF appears in the library.
 */
object ImportJobs {
    /** True when the PDF is pictures of pages: most of its first pages carry no text. */
    suspend fun isScanned(file: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                val n = minOf(doc.numberOfPages, 6)
                if (n == 0) return@use false
                val stripper = PDFTextStripper()
                var empty = 0
                for (p in 1..n) {
                    stripper.startPage = p
                    stripper.endPage = p
                    if (stripper.getText(doc).count { it.isLetterOrDigit() } < 25) empty++
                }
                empty * 2 > n
            }
        }.getOrDefault(false) // protected or unreadable: import as it is
    }

    /**
     * Renders every page of [pdf] into a job and queues it. With [replace] the finished searchable
     * PDF takes the place of [pdf]. Returns the page count.
     */
    suspend fun fromPdf(context: Context, pdf: File, title: String, replace: Boolean = false, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) {
            val dir = OcrWorker.newJobDir(context).apply { mkdirs() }
            val pages = ArrayList<ScanPage>()
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    for (i in 0 until r.pageCount) {
                        currentCoroutineContext().ensureActive()
                        r.openPage(i).use { page ->
                            // About 300 dpi for a typical page, capped for memory.
                            val scale = (3300f / max(page.width, page.height)).coerceAtMost(300f / 72f)
                            val bmp = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val f = File(dir, "page_${i + 1}_${UUID.randomUUID().toString().take(6)}.jpg")
                            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                            bmp.recycle()
                            // A photo inside a PDF may still show the desk around the page.
                            pages += ScanPage(source = f.name, look = Look.COLOUR, quad = PageImage.detectPageInPdf(f))
                        }
                        onProgress(i + 1, r.pageCount)
                    }
                }
            }
            queue(context, dir, pages, title, if (replace) pdf else null)
            pages.size
        }

    /**
     * "Make searchable" on a library document: a scan is replaced by its searchable version; a PDF
     * that already has text gets a new searchable copy next to it.
     */
    suspend fun makeSearchable(context: Context, file: File, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val scanned = isScanned(file)
        val title = if (scanned) file.nameWithoutExtension else file.nameWithoutExtension + " (searchable)"
        return fromPdf(context, file, title, replace = scanned, onProgress = onProgress)
    }

    /** Copies photos into a job (each page found and cropped) and queues it. Returns the page count. */
    suspend fun fromImages(context: Context, uris: List<Uri>, title: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) {
            val dir = OcrWorker.newJobDir(context).apply { mkdirs() }
            val pages = ArrayList<ScanPage>()
            uris.forEachIndexed { i, uri ->
                currentCoroutineContext().ensureActive()
                runCatching {
                    val f = File(dir, "img_${i + 1}_${UUID.randomUUID().toString().take(6)}.jpg")
                    context.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
                    val quad = if (AppGraph.settings.value.autoCrop) PageImage.detectPage(f) else null
                    pages += ScanPage(source = f.name, look = AppGraph.settings.value.defaultLook, quad = quad)
                }
                onProgress(i + 1, uris.size)
            }
            queue(context, dir, pages, title)
            pages.size
        }

    /**
     * Converts a folder of page photos, such as one PDFCS saved with "Save original photos": the
     * photos in name order ("Page 001", "Page 002"…), each page found again (with this version's
     * page finder), turned and cleaned as the folder's pages.json says when it has one. Returns
     * the page count.
     */
    suspend fun fromFolder(context: Context, tree: Uri, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
            class Entry(val id: String, val name: String, val mime: String)
            val entries = ArrayList<Entry>()
            resolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ), null, null, null)?.use { c -> while (c.moveToNext()) entries += Entry(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "") }
            val saved = entries.firstOrNull { it.name == "pages.json" }?.let { e ->
                runCatching {
                    val text = resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, e.id))!!.use { it.readBytes().decodeToString() }
                    val arr = org.json.JSONArray(text)
                    List(arr.length()) { ScanPage.fromJson(arr.getJSONObject(it)) }.associateBy { it.source }
                }.getOrNull()
            }.orEmpty()
            val photos = entries.filter { it.mime.startsWith("image/") }.sortedWith { a, b -> naturalCompare(a.name, b.name) }
            check(photos.isNotEmpty()) { "That folder has no photos" }
            val dir = OcrWorker.newJobDir(context).apply { mkdirs() }
            val pages = ArrayList<ScanPage>()
            val look = AppGraph.settings.value.defaultLook
            photos.forEachIndexed { i, e ->
                currentCoroutineContext().ensureActive()
                runCatching {
                    val f = File(dir, "img_${i + 1}_${UUID.randomUUID().toString().take(6)}.jpg")
                    resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, e.id))!!.use { input ->
                        f.outputStream().use { input.copyTo(it) }
                    }
                    val before = saved[e.name]
                    pages += ScanPage(source = f.name, look = before?.look ?: look, rotation = before?.rotation ?: 0, quad = PageImage.detectPage(f))
                }
                onProgress(i + 1, photos.size)
            }
            val title = treeId.substringAfterLast('/').substringAfter(':').ifBlank { "Photos" }
            queue(context, dir, pages, title)
            pages.size
        }

    /** Orders "Page 2" before "Page 10". */
    private fun naturalCompare(a: String, b: String): Int {
        val split = Regex("""(\d+)|(\D+)""")
        val pa = split.findAll(a.lowercase()).map { it.value }.toList()
        val pb = split.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]; val y = pb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
            else x.compareTo(y)
            if (c != 0) return c
        }
        return pa.size.compareTo(pb.size)
    }

    private fun queue(context: Context, dir: File, pages: List<ScanPage>, title: String, replaces: File? = null) {
        check(pages.isNotEmpty()) { "Nothing could be read from that file" }
        ScanSession.writePages(dir, pages)
        val s = AppGraph.settings.value
        OcrWorker.enqueueDir(context, dir, title, s.ocrLanguages, s.quality, s.outputStyle, s.fontChoice, replaces)
    }
}
