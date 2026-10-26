package com.narl3yyy.pdfcs.camera

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How a cleaned camera page looks. */
enum class Look(val label: String) {
    /** The straightened photo as it was taken. */
    ORIGINAL("Original"),
    /** Lighting evened out, paper white, colour ink kept. */
    COLOUR("Enhanced"),
    /** Lighting evened out, shades of grey. */
    GREYSCALE("Grayscale"),
    /** Black ink on white paper only. */
    BLACK_WHITE("Black & white"),
}

/**
 * Cleans up a straightened camera photo: evens out the lighting so the paper turns white
 * (room-light colour casts, a hand's shadow, the shadow near a book's spine go with it), then
 * raises the contrast and drops isolated specks. Colour ink stays colour.
 *
 * The paper's illumination is estimated per channel on a coarse grid of blocks, from each
 * block's bright end (the paper, not the ink), smoothed into a continuous map and divided out.
 *
 * Ported from PdfCraft's `engine/src/camera_clean.rs` (MIT OR Apache-2.0, © the PdfCraft
 * authors). Pure Kotlin on ARGB arrays so it is unit-tested on the JVM.
 */
object CameraClean {
    const val MAX_LEVEL = 100

    /** Default strength for scanned book pages (evens lighting fully, removes specks). */
    const val BOOK_LEVEL = 65

    /**
     * Gives [argb] ([w] × [h]) the [look] at [level] (0 = unchanged … 100 = strongest), in place.
     * For black and white the level sets the threshold: higher keeps fainter strokes black.
     */
    fun apply(argb: IntArray, w: Int, h: Int, look: Look, level: Int = BOOK_LEVEL) {
        require(argb.size == w * h && w > 0 && h > 0) { "pixel buffer does not match its size" }
        when (look) {
            Look.ORIGINAL -> Unit
            Look.COLOUR -> clean(argb, w, h, level)
            Look.GREYSCALE -> { clean(argb, w, h, level); toGrey(argb) }
            Look.BLACK_WHITE -> {
                // The lighting evened out fully, then one threshold for the whole page.
                clean(argb, w, h, 50)
                val t = (0.45f + 0.4f * level.coerceIn(0, MAX_LEVEL) / MAX_LEVEL) * 255f
                for (i in argb.indices) argb[i] = if (lum(argb[i]) < t) BLACK else WHITE
                despeckle(argb, w, h)
            }
        }
    }

    /** Evens out the lighting and stretches the contrast at [level], in place. */
    fun clean(argb: IntArray, w: Int, h: Int, level: Int) {
        val lv = level.coerceIn(0, MAX_LEVEL)
        if (lv == 0) return
        val k = lv.toFloat() / MAX_LEVEL
        val bg = Background.estimate(argb, w, h)
        // Lighting is evened out fully from half strength on; contrast keeps growing to the top.
        val flatten = min(2f * k, 1f)
        val white = 1f - 0.12f * k
        val black = 0.30f * k
        val range = max(white - black, 0.05f)
        val rowBg = FloatArray(w * 3)
        for (y in 0 until h) {
            bg.row(y, rowBg)
            for (x in 0 until w) {
                val i = y * w + x
                val p = argb[i]
                var out = 0xFF shl 24
                for (c in 0 until 3) {
                    val shift = 16 - 8 * c
                    val v = ((p shr shift) and 0xFF) / 255f
                    val b = max(rowBg[x * 3 + c], 0.05f)
                    val even = min(v / b, 1f)
                    val flat = v + (even - v) * flatten
                    val stretched = ((flat - black) / range).coerceIn(0f, 1f)
                    out = out or ((stretched * 255f).roundToInt() shl shift)
                }
                argb[i] = out
            }
        }
        if (lv >= 60) despeckle(argb, w, h)
    }

    fun toGrey(argb: IntArray) {
        for (i in argb.indices) {
            val l = lum(argb[i])
            argb[i] = (0xFF shl 24) or (l shl 16) or (l shl 8) or l
        }
    }

    /** Lighten dark pixels with at most one dark neighbour (specks), in place. */
    fun despeckle(argb: IntArray, w: Int, h: Int) {
        val dark = BooleanArray(argb.size) { lum(argb[it]) < 128 }
        for (y in 0 until h) for (x in 0 until w) {
            if (!dark[y * w + x]) continue
            var neighbours = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until w && yy in 0 until h && dark[yy * w + xx]) neighbours++
            }
            if (neighbours <= 1) argb[y * w + x] = WHITE
        }
    }

    private const val WHITE = -1
    private const val BLACK = 0xFF000000.toInt()
    private fun lum(p: Int) = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

    /** The paper's illumination: a grid of per-channel values, read back bilinearly. */
    internal class Background(private val cols: Int, private val rows: Int, private val block: Float, private val cells: FloatArray) {
        private fun cell(cx: Int, cy: Int, c: Int) = cells[(cy.coerceIn(0, rows - 1) * cols + cx.coerceIn(0, cols - 1)) * 3 + c]

        fun at(x: Int, y: Int, c: Int): Float {
            // Cell centres sit at (i + 0.5) × block.
            val fx = (x + 0.5f) / block - 0.5f
            val fy = (y + 0.5f) / block - 0.5f
            val x0 = kotlin.math.floor(fx)
            val y0 = kotlin.math.floor(fy)
            val ax = fx - x0
            val ay = fy - y0
            val ix = x0.toInt()
            val iy = y0.toInt()
            val top = cell(ix, iy, c) * (1 - ax) + cell(ix + 1, iy, c) * ax
            val bottom = cell(ix, iy + 1, c) * (1 - ax) + cell(ix + 1, iy + 1, c) * ax
            return top * (1 - ay) + bottom * ay
        }

        /** One image row of background values, three per pixel. */
        fun row(y: Int, out: FloatArray) {
            val w = out.size / 3
            for (x in 0 until w) for (c in 0 until 3) out[x * 3 + c] = at(x, y, c)
        }

        companion object {
            /**
             * Per block, the 90th percentile of each channel (bright paper, not dark ink); blocks
             * that are mostly ink borrow from their neighbours; then smoothed.
             */
            fun estimate(argb: IntArray, w: Int, h: Int): Background {
                val block = (min(w, h) / 16).coerceIn(8, 128)
                val cols = (w + block - 1) / block
                val rows = (h + block - 1) / block
                val cells = FloatArray(cols * rows * 3)
                val hist = Array(3) { IntArray(256) }
                for (by in 0 until rows) for (bx in 0 until cols) {
                    hist.forEach { it.fill(0) }
                    var n = 0
                    for (y in by * block until min((by + 1) * block, h)) for (x in bx * block until min((bx + 1) * block, w)) {
                        val p = argb[y * w + x]
                        hist[0][(p shr 16) and 0xFF]++
                        hist[1][(p shr 8) and 0xFF]++
                        hist[2][p and 0xFF]++
                        n++
                    }
                    val want = (n * 0.9f).toInt()
                    for (c in 0 until 3) {
                        var seen = 0
                        var v = 255
                        for (i in 0..255) { seen += hist[c][i]; if (seen > want) { v = i; break } }
                        cells[(by * cols + bx) * 3 + c] = v / 255f
                    }
                }
                fun get(src: FloatArray, x: Int, y: Int, c: Int) = src[(y.coerceIn(0, rows - 1) * cols + x.coerceIn(0, cols - 1)) * 3 + c]
                // A block darker than its neighbourhood's paper is mostly ink, a stamp or a picture:
                // take the brighter of itself and the 75th percentile of the 5 × 5 blocks around it.
                val lifted = FloatArray(cells.size)
                val around = FloatArray(25)
                for (y in 0 until rows) for (x in 0 until cols) for (c in 0 until 3) {
                    var k = 0
                    for (dy in -2..2) for (dx in -2..2) around[k++] = get(cells, x + dx, y + dy, c)
                    around.sort()
                    lifted[(y * cols + x) * 3 + c] = max(get(cells, x, y, c), around[18])
                }
                var cur = lifted
                repeat(2) {
                    val next = FloatArray(cur.size)
                    for (y in 0 until rows) for (x in 0 until cols) for (c in 0 until 3) {
                        var sum = 0f
                        for (dy in -1..1) for (dx in -1..1) sum += get(cur, x + dx, y + dy, c) / 9f
                        next[(y * cols + x) * 3 + c] = sum
                    }
                    cur = next
                }
                return Background(cols, rows, block.toFloat(), cur)
            }
        }
    }
}
