package com.narl3yyy.pdfcs.clearscan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.narl3yyy.pdfcs.core.Histogram
import com.narl3yyy.pdfcs.ocr.OcrLine
import com.narl3yyy.pdfcs.ocr.OcrWord
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A word set in a font at a position: what the page rebuild draws, before it decides to. */
class Placement(
    /** The text drawn, with a trailing space when another word follows on the line. */
    val text: String,
    val word: OcrWord,
    val line: OcrLine,
    val lineSize: Float,
    val face: FontFace,
    /** Font size in image pixels. */
    val size: Float,
    /** Horizontal scaling (1 = the font's own width). */
    val scale: Float,
    /** Baseline start, image pixels. */
    val x: Float,
    val baseline: Float,
) {
    val box: Rect get() = word.box
    val ink: String get() = text.trimEnd()
}

/** How a placement compares with the photo: overlap (0..1) and both amounts of ink. */
class Match(val fit: Float, val rendered: Int, val photo: Int)

/**
 * Fits recognised words to the ink on the page: sizes them from the font's real glyph extents,
 * stretches them to the word's width, and measures how well the typeset word covers the photo.
 */
class WordFitter(private val ink: InkMap, private val colour: Bitmap) {

    /** Font size in pixels for a line: median of (word ink height / glyph ink height in em). */
    fun lineSize(line: OcrLine, face: FontFace): Float? {
        val est = line.words.mapNotNull { w ->
            val sh = face.shape(FontLibrary.normalize(w.text)) ?: return@mapNotNull null
            if (sh.height < 0.25f || w.box.height() < 4) null else w.box.height() / sh.height
        }.sorted()
        return est.getOrNull(est.size / 2)
    }

    fun place(word: OcrWord, text: String, face: FontFace, lineSize: Float, line: OcrLine): Placement? {
        val sh = face.shape(text.trimEnd()) ?: return null
        if (sh.width <= 0f) return null
        // A word much taller or shorter than its line (a drop cap, a footnote mark) gets its own size.
        val own = if (sh.height > 0.25f) word.box.height() / sh.height else lineSize
        val size = if (own > lineSize * 1.6f || own < lineSize * 0.6f) own else lineSize
        val scale = word.box.width() / (sh.width * size)
        val x = word.box.left - sh.left * size * scale
        val fromLine = line.baselineAt(word.box.left)
        val fromWord = word.box.bottom + sh.bottom * size
        val baseline = if (fromLine != null && abs(fromLine - fromWord) < size * 0.25f) fromLine else fromWord
        return Placement(text, word, line, lineSize, face, size, scale, x, baseline)
    }

    // Sub-pixel, linearly scaled text: by default Android rounds every advance to a whole pixel,
    // which drifts across a long word and makes correct words look like misfits.
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        color = Color.BLACK
        fontFeatureSettings = "'kern' 0, 'liga' 0, 'clig' 0"
    }

    fun fit(p: Placement): Float = match(p).fit

    /**
     * Renders the word and compares it with the photo's ink: the share of each one's ink that has
     * the other's ink within one pixel (a soft photo is never pixel-exact, but a misread word
     * leaves strokes far from any rendered stroke, or the other way round).
     */
    fun match(p: Placement): Match {
        val pad = max(2, (p.box.height() * 0.2f).roundToInt())
        val r = Rect(p.box.left - pad, p.box.top - pad, p.box.right + pad, p.box.bottom + pad)
        val k = min(1f, 40f / r.height())
        val bw = ceil(r.width() * k).toInt().coerceAtLeast(1)
        val bh = ceil(r.height() * k).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8)
        paint.typeface = p.face.typeface
        paint.textSize = p.size * k
        paint.textScaleX = p.scale
        Canvas(bmp).drawText(p.ink, (p.x - r.left) * k, (p.baseline - r.top) * k, paint)
        val stride = bmp.rowBytes
        val buf = ByteBuffer.allocate(stride * bh)
        bmp.copyPixelsToBuffer(buf)
        bmp.recycle()
        val px = buf.array()
        // Local threshold: light or coloured ink (blue headings, grey captions) counts in full.
        val t = Histogram.otsu(ink.histogram(r, max(1, r.height() / 40))).coerceIn(ink.threshold - 30, 225)
        val ra = BooleanArray(bw * bh)
        val pb = BooleanArray(bw * bh)
        var rendered = 0
        for (y in 0 until bh) {
            val iy = (r.top + (y + 0.5f) / k).toInt()
            for (x in 0 until bw) {
                if ((px[y * stride + x].toInt() and 0xFF) > 110) { ra[y * bw + x] = true; rendered++ }
                val ix = (r.left + (x + 0.5f) / k).toInt()
                pb[y * bw + x] = ink.inside(ix, iy) && ink.value(ix, iy) < t
            }
        }
        fun near(g: BooleanArray, x: Int, y: Int): Boolean {
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until bw && yy in 0 until bh && g[yy * bw + xx]) return true
            }
            return false
        }
        var aTotal = 0; var aHit = 0; var bTotal = 0; var bHit = 0
        for (y in 0 until bh) for (x in 0 until bw) {
            val i = y * bw + x
            if (ra[i]) { aTotal++; if (near(pb, x, y)) aHit++ }
            if (pb[i]) { bTotal++; if (near(ra, x, y)) bHit++ }
        }
        val union = aTotal + bTotal
        return Match(if (union == 0) 0f else (aHit + bHit).toFloat() / union, (rendered / (k * k)).roundToInt(), inkArea(p.box))
    }

    /**
     * Ink in [box] as an equivalent area of solid ink: the total darkness below the paper level
     * divided by the darkness of solid ink. Blur spreads ink out but keeps this total, so unlike a
     * thresholded pixel count it still tells bold strokes from regular ones in a soft photo.
     */
    private fun inkArea(box: Rect): Int {
        val hist = ink.histogram(box)
        val n = hist.sum()
        if (n == 0) return 0
        // Coloured ink never gets as dark as black ink: measure it against its own darkest tone.
        // Black ink is measured against the page's solid black, so thin regular strokes, which
        // blur to grey, are not mistaken for full-strength ink.
        val darkest = Histogram.percentile(hist, 0.03)
        val solid = if (darkest > ink.solid + 50 && isColoured(box)) darkest else ink.solid
        val range = (ink.paper - solid).coerceAtLeast(40)
        var dark = 0L
        for (v in 0 until ink.paper) dark += (ink.paper - v).coerceAtMost(range).toLong() * hist[v]
        return (dark / range).toInt()
    }

    private fun isColoured(box: Rect): Boolean {
        var chroma = 0L
        var n = 0
        val step = max(1, box.height() / 12)
        for (y in box.top.coerceAtLeast(0) until box.bottom.coerceAtMost(ink.h) step step)
            for (x in box.left.coerceAtLeast(0) until box.right.coerceAtMost(ink.w) step step) {
                if (ink.value(x, y) >= ink.threshold) continue
                val c = colour.getPixel(x, y)
                chroma += maxOf(Color.red(c), Color.green(c), Color.blue(c)) - minOf(Color.red(c), Color.green(c), Color.blue(c))
                n++
            }
        return n > 0 && chroma / n > 45
    }

    /**
     * Slant evidence for a line: how much sharper the column profile of its ink gets when its
     * words are sheared upright by an italic angle. Upright type gets blurrier (< 1), italic
     * type gets sharper (> 1).
     */
    fun slant(line: OcrLine): Float {
        val tan = 0.21f
        var straight = 0.0
        var sheared = 0.0
        for (w in line.words) {
            if (w.text.length < 2) continue
            val b = w.box
            val base = line.baselineAt(b.left) ?: b.bottom.toFloat()
            val off = ceil(tan * (b.height() + 2)).toInt() + 1
            val h0 = IntArray(b.width() + 1)
            val h1 = IntArray(b.width() + 2 * off + 1)
            val step = max(1, b.height() / 40)
            for (y in b.top.coerceAtLeast(0) until b.bottom.coerceAtMost(ink.h) step step) {
                val shift = (tan * (base - y)).roundToInt()
                for (x in b.left.coerceAtLeast(0) until b.right.coerceAtMost(ink.w)) {
                    if (ink.value(x, y) >= ink.threshold) continue
                    val cx = x - b.left
                    h0[cx]++
                    val sx = cx - shift + off
                    if (sx in h1.indices) h1[sx]++
                }
            }
            for (v in h0) straight += v.toDouble() * v
            for (v in h1) sheared += v.toDouble() * v
        }
        return if (straight <= 0.0) 1f else (sheared / straight).toFloat()
    }

    /** True when the word sits on (near-)white paper rather than on a picture or tinted panel. */
    fun onPaper(box: Rect): Boolean {
        var sum = 0L
        var n = 0
        val step = max(1, box.height() / 12)
        for (y in box.top.coerceAtLeast(0) until box.bottom.coerceAtMost(ink.h) step step)
            for (x in box.left.coerceAtLeast(0) until box.right.coerceAtMost(ink.w) step step) {
                val v = ink.value(x, y)
                if (v >= ink.threshold) { sum += v; n++ }
            }
        return n == 0 || sum / n > 200
    }

    /** Average colour of the word's ink; near-neutral ink is printed as deep grey-black. */
    fun inkColour(box: Rect): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        val step = max(1, box.height() / 16)
        for (y in box.top.coerceAtLeast(0) until box.bottom.coerceAtMost(ink.h) step step)
            for (x in box.left.coerceAtLeast(0) until box.right.coerceAtMost(ink.w) step step) {
                if (ink.value(x, y) < ink.threshold * 0.8f) {
                    val c = colour.getPixel(x, y)
                    r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
                }
            }
        if (n == 0) return TEXT_BLACK
        val ar = (r / n).toInt(); val ag = (g / n).toInt(); val ab = (b / n).toInt()
        if (maxOf(ar, ag, ab) - minOf(ar, ag, ab) < 50) return TEXT_BLACK
        // Coloured ink (headings, links): keep the hue, deepen it a little to offset camera haze.
        return Color.rgb((ar * 0.85f).toInt(), (ag * 0.85f).toInt(), (ab * 0.85f).toInt())
    }

    companion object {
        val TEXT_BLACK = Color.rgb(26, 26, 26)
    }
}
