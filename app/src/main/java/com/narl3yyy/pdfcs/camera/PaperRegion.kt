package com.narl3yyy.pdfcs.camera

import com.narl3yyy.pdfcs.core.Histogram
import kotlin.math.max

/**
 * Fallback page finder for when [CameraEdges] sees no straight edges (a page filling most of the
 * photo, a curled book page): the largest bright region (paper) against a darker background.
 * Returns a normalised quad (TL, TR, BR, BL), or null when nothing clear is found.
 */
object PaperRegion {
    fun detect(argb: IntArray, w: Int, h: Int): List<Float>? {
        if (w < 8 || h < 8 || argb.size != w * h) return null
        val lum = IntArray(argb.size)
        val hist = IntArray(256)
        for (i in argb.indices) {
            val l = Histogram.luminance(argb[i])
            lum[i] = l
            hist[l]++
        }
        val t = Histogram.otsu(hist)
        // Paper must be clearly brighter than the background for this to be trustworthy.
        var fg = 0.0; var nf = 0; var bg = 0.0; var nb = 0
        for (l in lum) if (l > t) { fg += l; nf++ } else { bg += l; nb++ }
        if (nf == 0 || nb == 0 || fg / nf - bg / nb < 45) return null

        // Largest 4-connected bright component.
        val label = IntArray(argb.size)
        val stack = IntArray(argb.size)
        var best = 0; var bestSize = 0; var next = 0
        for (start in argb.indices) {
            if (lum[start] <= t || label[start] != 0) continue
            next++
            var sp = 0; var size = 0
            stack[sp++] = start; label[start] = next
            while (sp > 0) {
                val p = stack[--sp]; size++
                val x = p % w; val y = p / w
                for (n in intArrayOf(if (x > 0) p - 1 else -1, if (x < w - 1) p + 1 else -1, if (y > 0) p - w else -1, if (y < h - 1) p + w else -1)) {
                    if (n >= 0 && lum[n] > t && label[n] == 0) { label[n] = next; stack[sp++] = n }
                }
            }
            if (size > bestSize) { bestSize = size; best = next }
        }
        val area = bestSize.toFloat() / argb.size
        if (area < 0.2f || area > 0.93f) return null

        // Extreme points along the diagonals give the four corners.
        var tl = 0; var tr = 0; var br = 0; var bl = 0
        var sTl = Int.MAX_VALUE; var sTr = Int.MIN_VALUE; var sBr = Int.MIN_VALUE; var sBl = Int.MAX_VALUE
        for (p in argb.indices) {
            if (label[p] != best) continue
            val x = p % w; val y = p / w
            if (x + y < sTl) { sTl = x + y; tl = p }
            if (x + y > sBr) { sBr = x + y; br = p }
            if (x - y > sTr) { sTr = x - y; tr = p }
            if (x - y < sBl) { sBl = x - y; bl = p }
        }
        val fw = (w - 1).toFloat()
        val fh = (h - 1).toFloat()
        val q = listOf(tl, tr, br, bl).flatMap { listOf((it % w) / fw, (it / w) / fh) }
        // Reject degenerate shapes (a bright strip): both sides must be substantial.
        if (max(q[2] - q[0], q[4] - q[6]) < 0.3f || max(q[7] - q[1], q[5] - q[3]) < 0.3f) return null
        return q
    }
}
