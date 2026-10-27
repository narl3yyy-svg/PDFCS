package com.narl3yyy.pdfcs.camera

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Straightening after the perspective correction.
 *
 * - [angle] measures how far the text is turned. Text lines are what is printed most regularly on a
 *   page: projected along the right angle, its dark pixels pile up into sharp rows (the projection
 *   profile has the largest variance). A coarse search over ±[MAX_DEGREES] and a fine one around
 *   the best answer give the angle to a twentieth of a degree.
 * - [shear] measures how far the text columns lean (line starts drifting sideways down the page,
 *   left when the page's corners were found a little off): projected along the right slant, the
 *   lines' starts and ends make the sharpest steps (the column profile's gradient energy peaks).
 * - [trim] cuts strips of background left along the edges (a corner of the desk, the tablecloth):
 *   rows or columns, from the edge inward, that are mostly the colours found at the very edge and
 *   not paper.
 *
 * Pure Kotlin on ARGB arrays so it is unit-tested on the JVM.
 */
object Deskew {
    const val MAX_DEGREES = 8.0

    /** The text's skew in degrees (positive = turned clockwise), or 0 when there is too little text. */
    fun angle(argb: IntArray, w: Int, h: Int): Double {
        if (w < 64 || h < 64 || argb.size != w * h) return 0.0
        // Dark pixels of a page shrunk to at most 1000 pixels on its long side.
        val step = max(1, max(w, h) / 1000)
        val hist = IntArray(256)
        val sw = w / step
        val sh = h / step
        val lum = IntArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            val p = argb[(y * step) * w + x * step]
            val l = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            lum[y * sw + x] = l
            hist[l]++
        }
        val t = com.narl3yyy.pdfcs.core.Histogram.otsu(hist)
        var count = 0
        for (v in lum) if (v <= t) count++
        // Too little or too much ink: a picture, a blank page or a dark photo. Leave it alone.
        if (count < sw * sh * 0.005 || count > sw * sh * 0.45) return 0.0
        val keep = max(1, count / 120_000)
        val xs = FloatArray(count / keep + 1)
        val ys = FloatArray(count / keep + 1)
        var n = 0
        var seen = 0
        // Ignore a 3% frame: page edges and leftover background are not text.
        val mx = sw * 3 / 100
        val my = sh * 3 / 100
        for (y in my until sh - my) for (x in mx until sw - mx) {
            if (lum[y * sw + x] > t) continue
            if (seen++ % keep != 0 || n >= xs.size) continue
            xs[n] = x - sw / 2f
            ys[n] = y - sh / 2f
            n++
        }
        if (n < 500) return 0.0
        val diag = kotlin.math.hypot(sw.toDouble(), sh.toDouble()).toInt() + 2
        fun sharpness(deg: Double): Double {
            val r = Math.toRadians(deg)
            val s = sin(r).toFloat()
            val c = cos(r).toFloat()
            val bins = IntArray(diag)
            for (i in 0 until n) {
                val yy = (ys[i] * c - xs[i] * s + diag / 2).toInt()
                if (yy in bins.indices) bins[yy]++
            }
            var sum = 0.0
            for (b in bins) sum += b.toDouble() * b
            return sum
        }
        var best = 0.0
        var bestS = sharpness(0.0)
        var d = -MAX_DEGREES
        while (d <= MAX_DEGREES) {
            val sh2 = sharpness(d)
            if (sh2 > bestS) { bestS = sh2; best = d }
            d += 0.5
        }
        val coarse = best
        d = coarse - 0.5
        while (d <= coarse + 0.5) {
            val sh2 = sharpness(d)
            if (sh2 > bestS) { bestS = sh2; best = d }
            d += 0.05
        }
        // A flat profile (no clear text rows) gives no reliable angle.
        val flat = sharpness(best + 3.0)
        return if (bestS < flat * 1.08) 0.0 else best
    }

    const val MAX_SHEAR = 0.08

    /**
     * The sideways lean of the text columns, as x shift per pixel down (positive = lower lines further
     * right), or 0 when there is too little text or no clear lean. Measure on a level page.
     */
    fun shear(argb: IntArray, w: Int, h: Int): Double {
        if (w < 64 || h < 64 || argb.size != w * h) return 0.0
        val step = max(1, max(w, h) / 1000)
        val sw = w / step
        val sh = h / step
        val hist = IntArray(256)
        val lum = IntArray(sw * sh)
        val grey = BooleanArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            val p = argb[(y * step) * w + x * step]
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val l = (r * 299 + g * 587 + b * 114) / 1000
            lum[y * sw + x] = l
            grey[y * sw + x] = maxOf(r, g, b) - minOf(r, g, b) < 60
            hist[l]++
        }
        val t = com.narl3yyy.pdfcs.core.Histogram.otsu(hist)
        // Ink only: dark and colourless (a coloured cloth left at the edge has its own pattern).
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val mx = sw * 3 / 100
        val my = sh * 3 / 100
        for (y in my until sh - my) for (x in mx until sw - mx) {
            val i = y * sw + x
            if (lum[i] <= t && grey[i]) { xs += x - sw / 2f; ys += y - sh / 2f }
        }
        val n = xs.size
        if (n < 500 || n > sw * sh * 0.45) return 0.0
        // Every ink pixel: thinning them out regularly would itself draw stripes into the profile.
        val px = xs.toFloatArray()
        val py = ys.toFloatArray()
        val bins = IntArray(sw * 3)
        fun sharpness(s: Double): Double {
            bins.fill(0)
            for (i in px.indices) {
                val xx = (px[i] - s * py[i] + sw * 1.5).toInt()
                if (xx in bins.indices) bins[xx]++
            }
            var sum = 0.0
            for (i in 1 until bins.size) { val d = (bins[i] - bins[i - 1]).toDouble(); sum += d * d }
            return sum
        }
        val level = sharpness(0.0)
        var best = 0.0
        var bestS = level
        var s = -MAX_SHEAR
        while (s <= MAX_SHEAR + 1e-9) {
            val v = sharpness(s)
            if (v > bestS) { bestS = v; best = s }
            s += 0.0025
        }
        val coarse = best
        s = coarse - 0.0025
        while (s <= coarse + 0.0025) {
            val v = sharpness(s)
            if (v > bestS) { bestS = v; best = s }
            s += 0.0005
        }
        // A lean too small to see, or columns no sharper than upright: leave the page as it is.
        return if (abs(best) < 0.004 || bestS < level * 1.15) 0.0 else best
    }

    /**
     * How many pixels to cut from each edge (left, top, right, bottom): rows or columns from the edge
     * inward in which most pixels match the background and are not paper. At most [maxShare] of the
     * size per side.
     *
     * [background] is the colours of the scene around the page (sampled outside the found outline).
     * Only edge colours that also occur there count as background, so a printed band that runs to
     * the paper's edge (a datasheet's coloured footer) is never mistaken for the desk.
     */
    fun trim(argb: IntArray, w: Int, h: Int, background: List<Int>, maxShare: Double = 0.15): IntArray {
        val out = IntArray(4)
        if (w < 64 || h < 64 || argb.size != w * h) return out
        fun rgb(p: Int) = intArrayOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
        fun lum(c: IntArray) = (c[0] * 299 + c[1] * 587 + c[2] * 114) / 1000
        // Paper: the brighter half of the central region.
        val centre = ArrayList<IntArray>()
        for (y in h / 3 until 2 * h / 3 step max(1, h / 60)) for (x in w / 3 until 2 * w / 3 step max(1, w / 60)) centre += rgb(argb[y * w + x])
        val bright = centre.sortedBy { lum(it) }.drop(centre.size / 2)
        if (bright.isEmpty()) return out
        val paper = IntArray(3) { c -> bright.map { it[c] }.sorted()[bright.size / 2] }
        fun near(a: IntArray, b: IntArray, tol: Int) = abs(a[0] - b[0]) < tol && abs(a[1] - b[1]) < tol && abs(a[2] - b[2]) < tol
        val outside = background.map(::rgb)
        if (outside.isEmpty()) return out
        // Paper, or ink: ink is dark and colourless (a red check is dark but not ink).
        fun paperish(c: IntArray) = near(c, paper, 40) ||
            (lum(c) < lum(paper) * 0.45 && maxOf(c[0], c[1], c[2]) - minOf(c[0], c[1], c[2]) < 45)
        for (side in 0 until 4) {
            val horizontal = side == 1 || side == 3 // top / bottom: scan rows
            val extent = if (horizontal) h else w
            val across = if (horizontal) w else h
            fun px(line: Int, i: Int): IntArray {
                val l = if (side == 0 || side == 1) line else extent - 1 - line
                return if (horizontal) rgb(argb[l * w + i]) else rgb(argb[i * w + l])
            }
            // The scene: non-paper colours along the outermost line that are also seen around the page.
            val scene = (0 until across step max(1, across / 80)).map { px(0, it) }
                .filter { c -> !paperish(c) && outside.count { near(it, c, 30) } >= 2 }
            if (scene.size < 20) continue // the edge is already paper
            var cut = 0
            val limit = (extent * maxShare).toInt()
            val stepAcross = max(1, across / 120)
            while (cut < limit) {
                var sceneCount = 0
                var inkCount = 0
                var total = 0
                for (i in 0 until across step stepAcross) {
                    val c = px(cut, i)
                    total++
                    if (!paperish(c) && scene.any { near(it, c, 30) }) sceneCount++
                    else if (lum(c) < lum(paper) * 0.45 && maxOf(c[0], c[1], c[2]) - minOf(c[0], c[1], c[2]) < 45) inkCount++
                }
                // Text is never background: stop at the first line that carries ink.
                if (sceneCount < total * 0.35 || inkCount > total * 0.03) break
                cut++
            }
            out[side] = cut
        }
        return out
    }

    /** Rotates [argb] by [degrees] (positive = clockwise) about its centre, filling with white. */
    fun rotate(argb: IntArray, w: Int, h: Int, degrees: Double): IntArray {
        val r = Math.toRadians(-degrees)
        val c = cos(r)
        val s = sin(r)
        val cx = w / 2.0
        val cy = h / 2.0
        val out = IntArray(w * h) { -1 }
        for (y in 0 until h) for (x in 0 until w) {
            // Inverse mapping with bilinear sampling.
            val sx = c * (x - cx) - s * (y - cy) + cx
            val sy = s * (x - cx) + c * (y - cy) + cy
            val x0 = sx.toInt()
            val y0 = sy.toInt()
            if (x0 < 0 || y0 < 0 || x0 >= w - 1 || y0 >= h - 1) continue
            val fx = sx - x0
            val fy = sy - y0
            val p00 = argb[y0 * w + x0]; val p10 = argb[y0 * w + x0 + 1]
            val p01 = argb[(y0 + 1) * w + x0]; val p11 = argb[(y0 + 1) * w + x0 + 1]
            var v = 0xFF shl 24
            for (sh in intArrayOf(16, 8, 0)) {
                val a = (p00 shr sh) and 0xFF; val b = (p10 shr sh) and 0xFF
                val cc = (p01 shr sh) and 0xFF; val d = (p11 shr sh) and 0xFF
                val top = a + (b - a) * fx
                val bottom = cc + (d - cc) * fx
                v = v or ((top + (bottom - top) * fy).toInt().coerceIn(0, 255) shl sh)
            }
            out[y * w + x] = v
        }
        return out
    }
}
