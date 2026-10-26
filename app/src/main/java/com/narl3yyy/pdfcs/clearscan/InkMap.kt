package com.narl3yyy.pdfcs.clearscan

import android.graphics.Bitmap
import android.graphics.Rect
import com.narl3yyy.pdfcs.core.Histogram

/**
 * The OCR page's grey levels with its ink threshold and its paper and solid-ink levels.
 * Erasing a typeset word sets its pixels to paper, so what is left is graphics.
 */
class InkMap(gray: Bitmap) {
    val w = gray.width
    val h = gray.height
    private val lum = ByteArray(w * h)
    val threshold: Int
    val paper: Int
    val solid: Int

    init {
        val row = IntArray(w)
        val hist = IntArray(256)
        for (y in 0 until h) {
            gray.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val v = row[x] and 0xFF
                lum[y * w + x] = v.toByte()
                hist[v]++
            }
        }
        threshold = Histogram.otsu(hist).coerceIn(70, 200)
        // Page-wide paper and solid-ink levels (a word box is too small to estimate them).
        paper = (Histogram.percentile(hist, 0.5) - 6).coerceAtLeast(threshold + 10)
        solid = Histogram.percentile(hist, 0.004).coerceAtMost(threshold - 10)
    }

    fun value(x: Int, y: Int) = lum[y * w + x].toInt() and 0xFF
    fun inside(x: Int, y: Int) = x in 0 until w && y in 0 until h
    fun isInk(x: Int, y: Int) = inside(x, y) && value(x, y) < threshold

    fun erase(r: Rect) {
        val x0 = r.left.coerceIn(0, w)
        val x1 = r.right.coerceIn(0, w)
        if (x1 <= x0) return
        for (y in r.top.coerceIn(0, h) until r.bottom.coerceIn(0, h)) lum.fill(-1, y * w + x0, y * w + x1)
    }

    /** Histogram of the pixels in [r] (sampled every [step] pixels). */
    fun histogram(r: Rect, step: Int = 1): IntArray {
        val hist = IntArray(256)
        for (y in r.top.coerceAtLeast(0) until r.bottom.coerceAtMost(h) step step)
            for (x in r.left.coerceAtLeast(0) until r.right.coerceAtMost(w) step step) hist[value(x, y)]++
        return hist
    }

    /** Mean grey level of [r]. */
    fun mean(r: Rect, step: Int = 2): Float {
        val hist = histogram(r, step)
        val n = hist.sum()
        if (n == 0) return 255f
        var s = 0L
        for (v in hist.indices) s += v.toLong() * hist[v]
        return s.toFloat() / n
    }
}
