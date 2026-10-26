package com.narl3yyy.pdfcs.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.googlecode.leptonica.android.ReadFile
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.Closeable
import kotlin.math.roundToInt

/**
 * The one place PDFCS talks to Tesseract (LSTM engine, tessdata_best models): whole pages as
 * hOCR, single words for a second reading, and white-on-dark bands read inverted.
 */
class OcrEngine(context: Context, languages: List<String>) : Closeable {
    val api = TessBaseAPI()

    init {
        OcrLanguages.ensureBundled(context)
        val installed = OcrLanguages.installed(context)
        val langs = languages.filter { it in installed }.ifEmpty { listOf(OcrLanguages.BUNDLED) }
        check(api.init(OcrLanguages.dataRoot(context).path, langs.joinToString("+"), TessBaseAPI.OEM_LSTM_ONLY)) {
            "Could not start the OCR engine"
        }
        // Page size in a PDF is pixels / DPI; 300 is what a flatbed scan would use.
        api.setVariable("user_defined_dpi", DPI.toString())
        api.setVariable("preserve_interword_spaces", "1")
    }

    /** Recognises a whole page; returns its lines and words. */
    fun readPage(gray: Bitmap): List<OcrLine> {
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        val pix = ReadFile.readBitmap(gray) ?: error("Could not read the page image")
        return try {
            api.setImage(pix)
            Hocr.parse(api.getHOCRText(0).orEmpty())
        } finally {
            pix.recycle()
        }
    }

    /** Reads one word on its own: cropped with a white margin and enlarged to a comfortable size. */
    fun readWord(gray: Bitmap, box: Rect): String? = runCatching {
        val crop = cropScaled(gray, box, margin = 0.35f, targetHeight = 64f, invert = false) ?: return null
        try {
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_WORD
            api.setImage(crop)
            api.getUTF8Text()?.trim()?.takeIf { it.isNotEmpty() && '\n' !in it }
        } finally {
            crop.recycle()
        }
    }.getOrNull()

    /**
     * Reads light text on a dark band (a coloured footer, a table header) by inverting it first.
     * Word boxes come back in page pixels.
     */
    fun readInverted(gray: Bitmap, region: Rect): List<OcrLine> = runCatching {
        val scale = (48f / 30f).coerceAtLeast(1f)
        val crop = cropScaled(gray, region, margin = 0f, targetHeight = region.height() * scale, invert = true) ?: return emptyList()
        try {
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            api.setImage(crop)
            val k = crop.height.toFloat() / region.height()
            Hocr.parse(api.getHOCRText(0).orEmpty()).map { line -> line.mapped(k, region.left, region.top) }
        } finally {
            crop.recycle()
        }
    }.getOrDefault(emptyList())

    fun stop() = api.stop()

    override fun close() = api.recycle()

    private fun cropScaled(gray: Bitmap, box: Rect, margin: Float, targetHeight: Float, invert: Boolean): Bitmap? {
        if (box.width() <= 0 || box.height() <= 0) return null
        val pad = (box.height() * margin).roundToInt()
        val r = Rect(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad)
        val k = (targetHeight / box.height()).coerceIn(1f, 4f)
        val w = (r.width() * k).roundToInt().coerceAtLeast(1)
        val h = (r.height() * k).roundToInt().coerceAtLeast(1)
        val src = Rect(r.left.coerceAtLeast(0), r.top.coerceAtLeast(0), r.right.coerceAtMost(gray.width), r.bottom.coerceAtMost(gray.height))
        if (src.isEmpty) return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        if (invert) {
            paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(gray, src, RectF((src.left - r.left) * k, (src.top - r.top) * k, (src.right - r.left) * k, (src.bottom - r.top) * k), paint)
        }
        return out
    }

    companion object {
        const val DPI = 300
    }
}
