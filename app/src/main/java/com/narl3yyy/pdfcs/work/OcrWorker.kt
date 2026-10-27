package com.narl3yyy.pdfcs.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.narl3yyy.pdfcs.AppGraph
import com.narl3yyy.pdfcs.camera.Look
import com.narl3yyy.pdfcs.camera.PageImage
import com.narl3yyy.pdfcs.clearscan.DigitalPdfWriter
import com.narl3yyy.pdfcs.clearscan.PageRebuilder
import com.narl3yyy.pdfcs.clearscan.PhotoPdfWriter
import com.narl3yyy.pdfcs.library.FontChoice
import com.narl3yyy.pdfcs.library.OutputStyle
import com.narl3yyy.pdfcs.library.PdfQuality
import com.narl3yyy.pdfcs.library.ScanPage
import com.narl3yyy.pdfcs.library.ScanSession
import com.narl3yyy.pdfcs.ocr.OcrEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext

/**
 * Turns a detached scan (a folder of photos + pages.json) into one searchable PDF in the library.
 * Runs as a foreground job so a 300-page book keeps going with the screen off.
 */
class OcrWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val title = inputData.getString(KEY_TITLE) ?: "Scan"
    private val notificationId = id.hashCode()

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(0, 0)

    private fun foreground(done: Int, total: Int): ForegroundInfo {
        val n = Notifications.progress(applicationContext, title, done, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, n)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val jobDir = File(requireNotNull(inputData.getString(KEY_JOB_DIR)))
        val languages = inputData.getString(KEY_LANGS)?.split('+')?.filter { it.isNotBlank() }.orEmpty()
        val quality = PdfQuality.entries.firstOrNull { it.name == inputData.getString(KEY_QUALITY) } ?: PdfQuality.BALANCED
        val style = OutputStyle.entries.firstOrNull { it.name == inputData.getString(KEY_STYLE) } ?: OutputStyle.DIGITAL
        val fontChoice = FontChoice.entries.firstOrNull { it.name == inputData.getString(KEY_FONT) } ?: FontChoice.AUTO
        val digital = style == OutputStyle.DIGITAL && languages.all { it in PageRebuilder.SUPPORTED_LANGUAGES }
        val pages = ScanSession.readPages(jobDir)
        val work = File(applicationContext.cacheDir, "ocr_$id").apply { mkdirs() }
        var engine: OcrEngine? = null
        try {
            check(pages.isNotEmpty()) { "There are no pages to convert" }
            runCatching { setForeground(foreground(0, pages.size)) }
            reportProgress(0, pages.size)
            val ocr = OcrEngine(applicationContext, languages).also { engine = it }
            val produced = if (digital) digital(ocr, fontChoice, jobDir, pages, work) else photos(ocr, quality, jobDir, pages, work)
            check(produced.length() > 0) { "The PDF could not be written" }
            // A converted import takes the place of the scanned original, under the same name.
            val replaces = inputData.getString(KEY_REPLACES)?.let(::File)?.takeIf { it.exists() }
            val target = replaces?.also { it.delete() } ?: AppGraph.documents.uniqueFile(title)
            if (!produced.renameTo(target)) produced.copyTo(target, overwrite = false)
            jobDir.deleteRecursively()
            AppGraph.documents.refresh()
            Notifications.finished(applicationContext, notificationId, title, true, "${pages.size} pages, searchable")
            Log.i(LOG_TAG, "finished: ${pages.size} pages -> ${target.name}")
            Result.success(workDataOf(KEY_OUTPUT to target.path, OUT_TITLE to title))
        } catch (e: CancellationException) {
            engine?.stop()
            keepPages(jobDir)
            throw e
        } catch (t: Throwable) {
            val restored = keepPages(jobDir)
            val msg = (t.message ?: t.javaClass.simpleName) + if (restored) " — your pages were kept." else ""
            Notifications.finished(applicationContext, notificationId, title, false, msg)
            Log.w(LOG_TAG, "failed: $msg", t)
            Result.failure(workDataOf(KEY_ERROR to msg, OUT_TITLE to title))
        } finally {
            runCatching { engine?.close() }
            work.deleteRecursively()
        }
    }

    /** Digital text: every page rebuilt with real fonts. */
    private suspend fun digital(ocr: OcrEngine, font: FontChoice, jobDir: File, pages: List<ScanPage>, work: File): File {
        val out = File(work, "out.pdf")
        val rebuilder = PageRebuilder(applicationContext, ocr, font)
        DigitalPdfWriter().use { writer ->
            pages.forEachIndexed { index, page ->
                currentCoroutineContext().ensureActive()
                // The rebuilt page needs clean white paper behind its figures.
                val clean = if (page.look == Look.ORIGINAL) page.copy(look = Look.COLOUR) else page
                val full = PageImage.upscaleTo(PageImage.render(File(jobDir, page.source), clean, DIGITAL_MAX_SIDE), OCR_MAX_SIDE)
                val gray = PageImage.forOcr(full)
                try {
                    val rebuilt = rebuilder.rebuild(full, gray)
                    try { writer.add(rebuilt) } finally { rebuilt.recycle() }
                } finally {
                    gray.recycle()
                    full.recycle()
                }
                reportProgress(index + 1, pages.size)
            }
            writer.save(out, title)
        }
        return out
    }

    /** Original photos: each page image with Tesseract's invisible text layer. */
    private suspend fun photos(ocr: OcrEngine, quality: PdfQuality, jobDir: File, pages: List<ScanPage>, work: File): File {
        val base = File(work, "out")
        PhotoPdfWriter(ocr).use { writer ->
            writer.begin(base, title)
            pages.forEachIndexed { index, page ->
                currentCoroutineContext().ensureActive()
                val jpeg = File(work, "page.jpg")
                val full = PageImage.render(File(jobDir, page.source), page, OCR_MAX_SIDE)
                try {
                    val longSide = maxOf(full.width, full.height)
                    val display = if (longSide > quality.maxSide) {
                        val s = quality.maxSide.toFloat() / longSide
                        Bitmap.createScaledBitmap(full, (full.width * s).toInt(), (full.height * s).toInt(), true)
                    } else full
                    jpeg.outputStream().use { display.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality, it) }
                    if (display !== full) display.recycle()
                    val gray = PageImage.forOcr(full)
                    try {
                        check(writer.add(gray, jpeg)) { "Recognition failed on page ${index + 1}" }
                    } finally {
                        gray.recycle()
                    }
                } finally {
                    full.recycle()
                }
                reportProgress(index + 1, pages.size)
            }
            writer.end()
        }
        return File(base.path + ".pdf")
    }

    private suspend fun reportProgress(done: Int, total: Int) {
        setProgress(workDataOf(PROGRESS_DONE to done, PROGRESS_TOTAL to total, KEY_TITLE to title))
        runCatching { setForeground(foreground(done, total)) }
    }

    /** A scan goes back to the scan screen; an import's original PDF is still in the library. */
    private fun keepPages(jobDir: File): Boolean =
        if (inputData.getString(KEY_REPLACES) != null) { jobDir.deleteRecursively(); false } else AppGraph.session.restoreFrom(jobDir)

    companion object {
        const val TAG = "ocr"
        private const val LOG_TAG = "OcrWorker"
        const val KEY_JOB_DIR = "jobDir"
        const val KEY_TITLE = "title"
        const val KEY_LANGS = "langs"
        const val KEY_QUALITY = "quality"
        const val KEY_STYLE = "style"
        const val KEY_FONT = "font"
        const val KEY_OUTPUT = "output"
        const val KEY_REPLACES = "replaces"
        const val KEY_ERROR = "error"

        /**
         * Jobs are chained, and WorkManager feeds each finished job's output into the next one's
         * input, so results never reuse an input key.
         */
        const val OUT_TITLE = "outTitle"
        const val PROGRESS_DONE = "done"
        const val PROGRESS_TOTAL = "total"

        /** OCR resolution for photo-mode pages: enough for small print, bounded for memory. */
        const val OCR_MAX_SIDE = 3600

        /** Rebuilding pages reads small print best from the photo's full detail (a 16 MP photo). */
        const val DIGITAL_MAX_SIDE = 4600

        /** Hands the current scan to a background job. Returns false if there was nothing to convert. */
        fun enqueue(
            context: Context,
            title: String,
            languages: List<String>,
            quality: PdfQuality,
            style: OutputStyle,
            font: FontChoice,
        ): Boolean {
            val session = AppGraph.session
            if (session.pages.value.isEmpty()) return false
            val jobDir = newJobDir(context)
            if (!session.detachInto(jobDir)) return false
            enqueueDir(context, jobDir, title, languages, quality, style, font)
            return true
        }

        /** A fresh folder for a job's pages. */
        fun newJobDir(context: Context) = File(context.filesDir, "jobs/${UUID.randomUUID()}")

        /** Queues the pages in [jobDir] (photos + pages.json) for conversion into the library. */
        fun enqueueDir(
            context: Context,
            jobDir: File,
            title: String,
            languages: List<String>,
            quality: PdfQuality,
            style: OutputStyle,
            font: FontChoice,
            replaces: File? = null,
        ) {
            val request = OneTimeWorkRequestBuilder<OcrWorker>()
                .addTag(TAG)
                .setInputData(
                    workDataOf(
                        KEY_JOB_DIR to jobDir.path,
                        KEY_TITLE to title,
                        KEY_LANGS to languages.joinToString("+"),
                        KEY_QUALITY to quality.name,
                        KEY_STYLE to style.name,
                        KEY_FONT to font.name,
                        KEY_REPLACES to replaces?.path,
                    ),
                )
                .build()
            // Jobs run one at a time: OCR already uses the CPU fully, and memory is the limit.
            WorkManager.getInstance(context).enqueueUniqueWork("ocr-queue", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
