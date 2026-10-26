package com.narl3yyy.pdfcs.ocr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class OcrLanguage(val code: String, val name: String)

/**
 * Tesseract language models. English ships inside the APK; others are fetched
 * on request from the official tesseract-ocr/tessdata_best repository
 * (the most accurate LSTM models).
 */
object OcrLanguages {
    const val BUNDLED = "eng"

    val ALL = listOf(
        OcrLanguage("eng", "English"),
        OcrLanguage("fra", "French"),
        OcrLanguage("deu", "German"),
        OcrLanguage("spa", "Spanish"),
        OcrLanguage("ita", "Italian"),
        OcrLanguage("por", "Portuguese"),
        OcrLanguage("nld", "Dutch"),
        OcrLanguage("swe", "Swedish"),
        OcrLanguage("nor", "Norwegian"),
        OcrLanguage("dan", "Danish"),
        OcrLanguage("fin", "Finnish"),
        OcrLanguage("pol", "Polish"),
        OcrLanguage("ces", "Czech"),
        OcrLanguage("hun", "Hungarian"),
        OcrLanguage("ron", "Romanian"),
        OcrLanguage("tur", "Turkish"),
        OcrLanguage("ell", "Greek"),
        OcrLanguage("rus", "Russian"),
        OcrLanguage("ukr", "Ukrainian"),
        OcrLanguage("heb", "Hebrew"),
        OcrLanguage("ara", "Arabic"),
        OcrLanguage("hin", "Hindi"),
        OcrLanguage("vie", "Vietnamese"),
        OcrLanguage("ind", "Indonesian"),
        OcrLanguage("tha", "Thai"),
        OcrLanguage("chi_sim", "Chinese (Simplified)"),
        OcrLanguage("chi_tra", "Chinese (Traditional)"),
        OcrLanguage("jpn", "Japanese"),
        OcrLanguage("kor", "Korean"),
        OcrLanguage("lat", "Latin"),
    )

    fun nameOf(code: String) = ALL.firstOrNull { it.code == code }?.name ?: code

    /** Directory passed to TessBaseAPI.init (it must contain a "tessdata" folder). */
    fun dataRoot(context: Context) = File(context.filesDir, "ocr")
    private fun tessdata(context: Context) = File(dataRoot(context), "tessdata")
    private fun modelFile(context: Context, code: String) = File(tessdata(context), "$code.traineddata")

    fun installed(context: Context): Set<String> =
        tessdata(context).listFiles { f -> f.name.endsWith(".traineddata") }
            ?.map { it.name.removeSuffix(".traineddata") }?.toSet().orEmpty() + BUNDLED

    fun sizeOnDisk(context: Context, code: String): Long = modelFile(context, code).length()

    /** Copies the bundled English model out of the APK (Tesseract needs a real file). */
    @Synchronized
    fun ensureBundled(context: Context) {
        val target = modelFile(context, BUNDLED)
        val expected = context.assets.openFd("tessdata/$BUNDLED.traineddata").use { it.length }
        if (target.length() == expected) return
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        context.assets.open("tessdata/$BUNDLED.traineddata").use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        tmp.renameTo(target)
    }

    suspend fun download(context: Context, code: String, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val url = URL("https://github.com/tesseract-ocr/tessdata_best/raw/main/$code.traineddata")
        val target = modelFile(context, code)
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".download")
        var conn = url.openConnection() as HttpURLConnection
        try {
            // GitHub redirects raw files to its CDN; follow it (HttpURLConnection only follows same-protocol).
            var redirects = 0
            while (true) {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = false
                val code2 = conn.responseCode
                if (code2 in 300..399 && redirects < 5) {
                    val next = URL(conn.url, conn.getHeaderField("Location"))
                    conn.disconnect()
                    conn = next.openConnection() as HttpURLConnection
                    redirects++
                    continue
                }
                check(code2 == 200) { "Download failed (HTTP $code2)" }
                break
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            check(tmp.length() > 100_000) { "Downloaded model looks incomplete" }
            check(tmp.renameTo(target)) { "Could not save the language model" }
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }

    fun delete(context: Context, code: String) {
        if (code != BUNDLED) modelFile(context, code).delete()
    }
}
