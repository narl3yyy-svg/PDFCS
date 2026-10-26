package com.narl3yyy.pdfcs.clearscan

import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Groups the ink left after typeset words are erased into rectangles: figures, photos, table
 * rules, logos, stamps. Specks and page-edge shadows are dropped.
 */
object GraphicsFinder {
    fun find(ink: InkMap, bodySize: Float, wordBoxes: List<Rect>): List<Rect> {
        val cell = max(3, (bodySize / 5f).roundToInt())
        val cols = (ink.w + cell - 1) / cell
        val rows = (ink.h + cell - 1) / cell
        val count = IntArray(cols * rows)
        for (y in 0 until ink.h) {
            val cy = (y / cell) * cols
            for (x in 0 until ink.w) if (ink.value(x, y) < ink.threshold) count[cy + x / cell]++
        }
        // Dilate so the strokes of one drawing join into one region.
        val grown = BooleanArray(count.size)
        for (y in 0 until rows) for (x in 0 until cols) {
            if (count[y * cols + x] < 2) continue
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy
                val xx = x + dx
                if (yy in 0 until rows && xx in 0 until cols) grown[yy * cols + xx] = true
            }
        }
        val seen = BooleanArray(count.size)
        val stack = IntArray(count.size)
        val out = ArrayList<Rect>()
        val minInk = max(16f, bodySize * bodySize * 0.02f)
        for (start in grown.indices) {
            if (!grown[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var x0 = cols; var y0 = rows; var x1 = 0; var y1 = 0; var total = 0
            while (sp > 0) {
                val c = stack[--sp]
                val cx = c % cols
                val cy = c / cols
                total += count[c]
                x0 = min(x0, cx); x1 = max(x1, cx); y0 = min(y0, cy); y1 = max(y1, cy)
                if (cx > 0) { val n = c - 1; if (grown[n] && !seen[n]) { seen[n] = true; stack[sp++] = n } }
                if (cx < cols - 1) { val n = c + 1; if (grown[n] && !seen[n]) { seen[n] = true; stack[sp++] = n } }
                if (cy > 0) { val n = c - cols; if (grown[n] && !seen[n]) { seen[n] = true; stack[sp++] = n } }
                if (cy < rows - 1) { val n = c + cols; if (grown[n] && !seen[n]) { seen[n] = true; stack[sp++] = n } }
            }
            if (total < minInk) continue // dust, specks, leftovers of erased words
            val r = Rect(x0 * cell, y0 * cell, min(ink.w, (x1 + 1) * cell), min(ink.h, (y1 + 1) * cell))
            val touchesEdge = r.left <= 0 || r.top <= 0 || r.right >= ink.w || r.bottom >= ink.h
            val thin = r.width() < ink.w * 0.02f || r.height() < ink.h * 0.02f
            // A thin strip along the border with no words in it is the page edge or the shadow of
            // the next page; a footer band or a rule with text is content.
            if (touchesEdge && thin && wordBoxes.none { Rect.intersects(it, r) }) continue
            out += r
        }
        return out
    }

    /**
     * Solid dark panels (a coloured footer band, a table's header row): blocks whose median grey
     * is dark, grouped. Their light text is unreadable as it is and gets read inverted.
     */
    fun darkPanels(ink: InkMap, bodySize: Float): List<Rect> {
        val cell = max(4, (bodySize / 2f).roundToInt())
        val cols = (ink.w + cell - 1) / cell
        val rows = (ink.h + cell - 1) / cell
        val dark = BooleanArray(cols * rows)
        val hist = IntArray(256)
        for (cy in 0 until rows) for (cx in 0 until cols) {
            hist.fill(0)
            var n = 0
            for (y in cy * cell until min((cy + 1) * cell, ink.h) step 2) for (x in cx * cell until min((cx + 1) * cell, ink.w) step 2) {
                hist[ink.value(x, y)]++; n++
            }
            var acc = 0
            var median = 255
            for (v in 0..255) { acc += hist[v]; if (acc * 2 >= n) { median = v; break } }
            dark[cy * cols + cx] = n > 0 && median < 135
        }
        val seen = BooleanArray(dark.size)
        val stack = IntArray(dark.size)
        val out = ArrayList<Rect>()
        for (start in dark.indices) {
            if (!dark[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var x0 = cols; var y0 = rows; var x1 = 0; var y1 = 0; var cells = 0
            while (sp > 0) {
                val c = stack[--sp]
                val cx = c % cols
                val cy = c / cols
                cells++
                x0 = min(x0, cx); x1 = max(x1, cx); y0 = min(y0, cy); y1 = max(y1, cy)
                for (n in intArrayOf(if (cx > 0) c - 1 else -1, if (cx < cols - 1) c + 1 else -1, if (cy > 0) c - cols else -1, if (cy < rows - 1) c + cols else -1)) {
                    if (n >= 0 && dark[n] && !seen[n]) { seen[n] = true; stack[sp++] = n }
                }
            }
            val r = Rect(x0 * cell, y0 * cell, min(ink.w, (x1 + 1) * cell), min(ink.h, (y1 + 1) * cell))
            // A panel holds a line of text: wide enough, a line or more tall, mostly filled.
            val solid = cells.toFloat() / ((x1 - x0 + 1) * (y1 - y0 + 1))
            if (r.width() >= bodySize * 3 && r.height() >= bodySize * 0.8f && r.height() < ink.h / 3 && solid > 0.6f) out += r
        }
        return out
    }
}
