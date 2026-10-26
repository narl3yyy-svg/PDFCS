package com.narl3yyy.pdfcs.ocr

import android.graphics.Rect

data class OcrWord(val text: String, val box: Rect, val confidence: Int)

/** A text line; [baseline] is hOCR's (slope, offset) relative to the bottom-left of [box]. */
data class OcrLine(val box: Rect, val baseline: Pair<Float, Float>?, val words: List<OcrWord>) {
    /** Baseline y (image pixels) at image x. */
    fun baselineAt(x: Int): Float? = baseline?.let { (slope, offset) -> box.bottom + offset + slope * (x - box.left) }

    /** This line read from an image scaled by [k] and cropped at ([dx], [dy]), in page pixels. */
    fun mapped(k: Float, dx: Int, dy: Int): OcrLine {
        fun m(r: Rect) = Rect((r.left / k).toInt() + dx, (r.top / k).toInt() + dy, (r.right / k).toInt() + dx, (r.bottom / k).toInt() + dy)
        return OcrLine(m(box), baseline?.let { (s, o) -> s to o / k }, words.map { it.copy(box = m(it.box)) })
    }
}

/** Minimal parser for the hOCR that Tesseract emits: lines and words with boxes and confidences. */
object Hocr {
    private val span = Regex(
        """<span class=['"](ocr_line|ocr_caption|ocr_textfloat|ocr_header|ocrx_word)['"][^>]*?title=['"]([^'"]*)['"][^>]*>""",
    )
    private val bbox = Regex("""bbox (-?\d+) (-?\d+) (-?\d+) (-?\d+)""")
    private val baseline = Regex("""baseline (-?[\d.]+) (-?[\d.]+)""")
    private val wconf = Regex("""x_wconf (\d+)""")
    private val tag = Regex("<[^>]+>")

    fun parse(hocr: String): List<OcrLine> {
        val lines = ArrayList<OcrLine>()
        var lineBox: Rect? = null
        var lineBase: Pair<Float, Float>? = null
        var words = ArrayList<OcrWord>()
        fun flush() {
            val b = lineBox
            if (b != null && words.isNotEmpty()) lines += OcrLine(b, lineBase, words)
            words = ArrayList()
        }
        for (m in span.findAll(hocr)) {
            val title = m.groupValues[2]
            val box = bbox.find(title)?.groupValues?.drop(1)?.map { it.toInt() }
                ?.let { Rect(it[0], it[1], it[2], it[3]) } ?: continue
            if (m.groupValues[1] == "ocrx_word") {
                val end = hocr.indexOf("</span>", m.range.last + 1)
                if (end < 0) continue
                val text = unescape(hocr.substring(m.range.last + 1, end).replace(tag, "")).trim()
                if (text.isEmpty() || box.width() <= 0 || box.height() <= 0) continue
                words += OcrWord(text, box, wconf.find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 0)
            } else {
                flush()
                lineBox = box
                lineBase = baseline.find(title)?.let { it.groupValues[1].toFloat() to it.groupValues[2].toFloat() }
            }
        }
        flush()
        return lines
    }

    private fun unescape(s: String) = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
}
