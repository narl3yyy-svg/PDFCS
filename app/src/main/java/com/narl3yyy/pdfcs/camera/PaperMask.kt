package com.narl3yyy.pdfcs.camera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The page as a mask: grown from paper-coloured pixels near the centre to every neighbour that is
 * close in colour (gradual shading passes, the step at the paper's edge stops it), with the holes
 * left by text and pictures filled. Running [CameraEdges] on this mask instead of the photo sees
 * only the page outline: no tablecloth checks, no text lines.
 */
object PaperMask {
    private const val SIDE = 400

    /** The mask as an ARGB picture (white page on black) of the same size as the photo, or null. */
    fun build(argb: IntArray, w: Int, h: Int): IntArray? {
        val step = max(1, (max(w, h) + SIDE - 1) / SIDE)
        val sw = (w + step - 1) / step
        val sh = (h + step - 1) / step
        val r = FloatArray(sw * sh); val g = FloatArray(sw * sh); val b = FloatArray(sw * sh)
        for (sy in 0 until sh) for (sx in 0 until sw) {
            var rr = 0f; var gg = 0f; var bb = 0f; var n = 0
            for (y in sy * step until min((sy + 1) * step, h)) for (x in sx * step until min((sx + 1) * step, w)) {
                val p = argb[y * w + x]
                rr += (p shr 16) and 0xFF; gg += (p shr 8) and 0xFF; bb += p and 0xFF; n++
            }
            val i = sy * sw + sx
            r[i] = rr / n; g[i] = gg / n; b[i] = bb / n
        }
        fun lum(i: Int) = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
        // Seeds: the brighter half of a central box (paper, not ink).
        val seeds = ArrayList<Int>()
        for (y in sh * 2 / 5 until sh * 3 / 5) for (x in sw * 2 / 5 until sw * 3 / 5) seeds += y * sw + x
        if (seeds.isEmpty()) return null
        val sorted = seeds.sortedBy { lum(it) }
        val paperLum = lum(sorted[sorted.size * 3 / 4])
        val start = sorted.filter { lum(it) >= paperLum * 0.9f }
        if (start.isEmpty() || paperLum < 60f) return null
        // Grow over small colour steps between neighbours.
        val inPage = BooleanArray(sw * sh)
        val stack = IntArray(sw * sh)
        var sp = 0
        for (s in start) if (!inPage[s]) { inPage[s] = true; stack[sp++] = s }
        fun close(a: Int, c: Int) = abs(r[a] - r[c]) < STEP && abs(g[a] - g[c]) < STEP && abs(b[a] - b[c]) < STEP && lum(c) > paperLum * 0.55f
        while (sp > 0) {
            val p = stack[--sp]
            val x = p % sw
            val y = p / sw
            for (n in intArrayOf(if (x > 0) p - 1 else -1, if (x < sw - 1) p + 1 else -1, if (y > 0) p - sw else -1, if (y < sh - 1) p + sw else -1)) {
                if (n >= 0 && !inPage[n] && close(p, n)) { inPage[n] = true; stack[sp++] = n }
            }
        }
        // Fill holes: whatever the outside (grown from the border) cannot reach is page.
        val outside = BooleanArray(sw * sh)
        sp = 0
        for (x in 0 until sw) for (y in intArrayOf(0, sh - 1)) { val i = y * sw + x; if (!inPage[i] && !outside[i]) { outside[i] = true; stack[sp++] = i } }
        for (y in 0 until sh) for (x in intArrayOf(0, sw - 1)) { val i = y * sw + x; if (!inPage[i] && !outside[i]) { outside[i] = true; stack[sp++] = i } }
        while (sp > 0) {
            val p = stack[--sp]
            val x = p % sw
            val y = p / sw
            for (n in intArrayOf(if (x > 0) p - 1 else -1, if (x < sw - 1) p + 1 else -1, if (y > 0) p - sw else -1, if (y < sh - 1) p + sw else -1)) {
                if (n >= 0 && !inPage[n] && !outside[n]) { outside[n] = true; stack[sp++] = n }
            }
        }
        val pageCells = outside.count { !it }
        // A mask that took the whole picture (no contrast with the scene) or a speck tells nothing.
        if (pageCells < sw * sh * 0.08 || pageCells > sw * sh * 0.97) return null
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = if (outside[(y / step) * sw + x / step]) BLACK else WHITE
        return out
    }

    /** Largest colour step (per channel) between neighbouring cells of one surface. */
    private const val STEP = 9f
    private const val WHITE = -1
    private const val BLACK = 0xFF000000.toInt()
}
