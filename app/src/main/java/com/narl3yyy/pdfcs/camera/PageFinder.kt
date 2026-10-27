package com.narl3yyy.pdfcs.camera

import com.narl3yyy.pdfcs.core.Pt
import com.narl3yyy.pdfcs.core.Quad
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Finds the page in a photo and refuses to guess. Candidate outlines come from the sheet's straight
 * edges ([CameraEdges]) and from the bright paper region ([PaperRegion]); each is checked against
 * what a photographed page looks like:
 *
 * - its inside is mostly one paper colour (text and pictures are a minority),
 * - each side has a step in colour across it (a real edge, not a line of text),
 * - just outside it the scene looks different from the paper (not a box drawn on a tablecloth).
 *
 * The best outline is then grown where a strip beyond one of its sides still belongs to the page (a
 * coloured footer band, a margin above the first line). With no convincing outline the answer is
 * null: a wrong crop is worse than none, and the page rebuild removes leftover background anyway.
 */
object PageFinder {
    /** A found page: corners in picture pixels (TL, TR, BR, BL) and how sure the check is (0..1). */
    class Found(val corners: List<Pt>, val confidence: Double)

    private const val SAMPLE = 320

    /** Diagnostics hook for the benchmark. */
    internal var trace: ((String) -> Unit)? = null

    fun find(argb: IntArray, w: Int, h: Int): Found? {
        if (w < 32 || h < 32 || argb.size != w * h) return null
        val img = Small(argb, w, h, SAMPLE)
        val scored = candidates(argb, w, h).mapNotNull { (q, weight) ->
            Quad.order(q)?.let { o -> evaluate(img, o, w, h)?.let { o to Score(it.score * weight, it.paper, it.pIn, it.contrast, it.pOut) } }
        }
        if (scored.isEmpty()) return null
        var (best, bestScore) = scored.maxBy { it.second.score }
        // Shrink: an outline lined up with a pattern around the page (the squares of a tablecloth)
        // can outscore the page itself. A clear-edged outline inside it wins when the ring between
        // the two is the scene, not page.
        for ((q, s) in scored.sortedByDescending { it.second.score }) {
            val ratio = Quad.area(q) / Quad.area(best)
            if (ratio < 0.35 || ratio > 0.92 || s.contrast < 0.85 || s.pOut > 0.3 || s.score < bestScore.score * 0.5) continue
            if (!contains(best, q)) continue
            val (paperShare, matching) = stripMatch(img, q, best, w, h, s.paper) ?: continue
            trace?.invoke("shrink: ratio=%.2f paper=%.2f sceneMatch=%.2f".format(ratio, paperShare, matching))
            if (matching >= 0.7 && paperShare < 0.6) { best = q; bestScore = s; break }
        }
        // Grow: a larger outline containing this one wins when the extra strip is page, not scene.
        for ((q, s) in scored.sortedBy { Quad.area(it.first) }) {
            val ratio = Quad.area(q) / Quad.area(best)
            if (ratio <= 1.005 || ratio > 1.25) continue
            if (!contains(q, best)) { trace?.invoke("grow: ratio=%.3f not containing".format(ratio)); continue }
            if (stripBelongsToPage(img, best, q, w, h, bestScore.paper)) { best = q; bestScore = s }
        }
        // Extend sides over parts of the page the edges missed (a printed band at the foot).
        extendSides(img, best, w, h, bestScore.paper)?.let { ext ->
            evaluate(img, ext, w, h)?.let { s -> if (s.score >= bestScore.score * 0.9) { best = ext; bestScore = s } }
        }
        // Snap the chosen outline onto the photo's own edges, and keep it if it still checks out.
        CameraEdges.snap(argb, w, h, best)?.let { snapped ->
            val s = evaluate(img, snapped, w, h)
            val moved = snapped.zip(best).maxOf { (a, b) -> hypot(a.x - b.x, a.y - b.y) } / sqrt(Quad.area(best))
            trace?.invoke("snap: moved=%.3f score=%s".format(moved, s?.score?.let { "%.3f".format(it) }))
            if (s != null && moved < 0.035 && s.score >= bestScore.score * 0.9) { best = snapped; bestScore = s }
        }
        if (bestScore.score < MIN_SCORE) return null
        return Found(best, bestScore.score.coerceAtMost(1.0))
    }

    private const val MIN_SCORE = 0.08

    /** The picture box-filtered to a small working size, with sampling in picture coordinates. */
    private class Small(argb: IntArray, w: Int, h: Int, side: Int) {
        val step = max(1, (max(w, h) + side - 1) / side)
        val sw = (w + step - 1) / step
        val sh = (h + step - 1) / step
        val rgb = FloatArray(sw * sh * 3)
        val k = 1.0 / step

        init {
            for (sy in 0 until sh) for (sx in 0 until sw) {
                var r = 0f; var g = 0f; var b = 0f; var n = 0
                for (y in sy * step until min((sy + 1) * step, h)) for (x in sx * step until min((sx + 1) * step, w)) {
                    val p = argb[y * w + x]
                    r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
                }
                val i = (sy * sw + sx) * 3
                rgb[i] = r / n; rgb[i + 1] = g / n; rgb[i + 2] = b / n
            }
        }

        /** Colour at picture point (x, y), or null outside the picture. */
        fun at(x: Double, y: Double): FloatArray? {
            val sx = (x * k).toInt()
            val sy = (y * k).toInt()
            if (sx !in 0 until sw || sy !in 0 until sh) return null
            val i = (sy * sw + sx) * 3
            return floatArrayOf(rgb[i], rgb[i + 1], rgb[i + 2])
        }
    }

    internal class Score(val score: Double, val paper: FloatArray, val pIn: Double = 0.0, val contrast: Double = 0.0, val pOut: Double = 0.0)

    /**
     * Candidate outlines with a weight: the photo's straight edges, the same line search run on the
     * page mask ([PaperMask]: only the page's outline), and the bright paper region (rougher, so
     * weighted down).
     */
    private fun candidates(argb: IntArray, w: Int, h: Int): List<Pair<List<Pt>, Double>> {
        val out = ArrayList<Pair<List<Pt>, Double>>()
        CameraEdges.candidates(argb, w, h).forEach { out += it.corners to 1.0 }
        PaperMask.build(argb, w, h)?.let { mask -> CameraEdges.candidates(mask, w, h).take(4).forEach { out += it.corners to 1.0 } }
        PaperRegion.detect(argb, w, h)?.let { q -> out += List(4) { Pt(q[it * 2] * (w - 1.0), q[it * 2 + 1] * (h - 1.0)) } to 0.9 }
        return out
    }

    /** Every candidate with its score parts, for the benchmark's diagnostics. */
    internal fun analyse(argb: IntArray, w: Int, h: Int): List<Pair<List<Pt>, Score?>> {
        val img = Small(argb, w, h, SAMPLE)
        return candidates(argb, w, h).mapNotNull { (q, _) -> Quad.order(q)?.let { o -> o to evaluate(img, o, w, h) } }
    }

    private fun lum(c: FloatArray) = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]

    /** Paper-like: as bright as the paper (allowing for shadows) and the same hue. */
    private fun paperLike(c: FloatArray, paper: FloatArray): Boolean {
        val lp = lum(paper).coerceAtLeast(1f)
        val lc = lum(c).coerceAtLeast(1f)
        if (lc < 0.72f * lp) return false
        for (i in 0 until 3) if (abs(c[i] / lc - paper[i] / lp) > 0.12f) return false
        return true
    }

    private fun dist(a: FloatArray, b: FloatArray) = max(abs(a[0] - b[0]), max(abs(a[1] - b[1]), abs(a[2] - b[2])))

    /** A point inside the quad at bilinear parameters (u, v), 0..1 each. */
    private fun bilinear(q: List<Pt>, u: Double, v: Double): Pt {
        val top = Pt(q[0].x + (q[1].x - q[0].x) * u, q[0].y + (q[1].y - q[0].y) * u)
        val bottom = Pt(q[3].x + (q[2].x - q[3].x) * u, q[3].y + (q[2].y - q[3].y) * u)
        return Pt(top.x + (bottom.x - top.x) * v, top.y + (bottom.y - top.y) * v)
    }

    private fun evaluate(img: Small, q: List<Pt>, w: Int, h: Int): Score? {
        val areaShare = Quad.area(q) / (w.toDouble() * h)
        if (areaShare < 0.10 || areaShare > 0.985) return null
        // Inside: a grid in from the edges.
        val inside = ArrayList<FloatArray>()
        for (i in 0 until 18) for (j in 0 until 18) {
            val p = bilinear(q, 0.05 + 0.9 * (i + 0.5) / 18, 0.05 + 0.9 * (j + 0.5) / 18)
            img.at(p.x, p.y)?.let { inside += it }
        }
        if (inside.size < 100) return null
        // Paper colour: the median of the brighter half of the inside.
        val bright = inside.sortedBy { lum(it) }.drop(inside.size / 2)
        val paper = FloatArray(3) { c -> bright.map { it[c] }.sorted()[bright.size / 2] }
        val pIn = inside.count { paperLike(it, paper) }.toDouble() / inside.size
        if (pIn < 0.5) return null

        // Each side: contrast across it, and how paper-like the scene just outside is.
        val size = sqrt(Quad.area(q))
        val cx = q.sumOf { it.x } / 4
        val cy = q.sumOf { it.y } / 4
        var minContrast = 1.0
        var outsidePaper = 0
        var outsideCount = 0
        for (k in 0 until 4) {
            val a = q[k]
            val b = q[(k + 1) % 4]
            val len = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(1.0)
            var nx = -(b.y - a.y) / len
            var ny = (b.x - a.x) / len
            // Outward normal points away from the centre.
            val mx = (a.x + b.x) / 2
            val my = (a.y + b.y) / 2
            if ((mx - cx) * nx + (my - cy) * ny < 0) { nx = -nx; ny = -ny }
            var steps = 0
            var counted = 0
            for (i in 0 until 24) {
                val t = 0.08 + 0.84 * (i + 0.5) / 24
                val px = a.x + (b.x - a.x) * t
                val py = a.y + (b.y - a.y) * t
                val d = 0.015 * size
                val cin = img.at(px - nx * d, py - ny * d) ?: continue
                val cout = img.at(px + nx * d, py + ny * d) ?: continue
                counted++
                if (dist(cin, cout) > 12f) steps++
                img.at(px + nx * 0.05 * size, py + ny * 0.05 * size)?.let { far ->
                    outsideCount++
                    if (paperLike(far, paper) && dist(far, paper) < 14f) outsidePaper++
                }
            }
            // A side along the picture's border has nothing outside it to compare with: that is
            // the photo's frame, not evidence of the page's edge.
            val contrast = if (counted < 6) 0.25 else steps.toDouble() / counted
            minContrast = min(minContrast, contrast)
        }
        if (minContrast < 0.2) return null
        val pOut = if (outsideCount == 0) 0.0 else outsidePaper.toDouble() / outsideCount
        // The scene's own colours (well outside): an outline whose inside is full of them is a box
        // drawn on the tablecloth or desk, not the page. Paper-coloured scene parts are left out,
        // or a white desk would count the page itself as scene.
        val scene = ArrayList<FloatArray>()
        for (k in 0 until 4) for (i in 0 until 12) for (f in doubleArrayOf(0.06, 0.11)) {
            val u = (i + 0.5) / 12
            val edge = when (k) { 0 -> bilinear(q, u, 0.0); 1 -> bilinear(q, 1.0, u); 2 -> bilinear(q, u, 1.0); else -> bilinear(q, 0.0, u) }
            val dx = edge.x - cx
            val dy = edge.y - cy
            val d = hypot(dx, dy).coerceAtLeast(1.0)
            img.at(edge.x + dx / d * f * size, edge.y + dy / d * f * size)?.takeUnless { paperLike(it, paper) }?.let { scene += it }
        }
        // The photo's own outer border is scene too (it matters for outlines that hug the frame).
        for (i in 0 until 40) {
            val t = (i + 0.5) / 40
            for (p in listOf(Pt(t * w, 0.01 * h), Pt(t * w, 0.99 * h), Pt(0.01 * w, t * h), Pt(0.99 * w, t * h))) {
                if (!strictlyInside(q, p)) img.at(p.x, p.y)?.takeUnless { paperLike(it, paper) }?.let { scene += it }
            }
        }
        val nonPaper = inside.filterNot { paperLike(it, paper) }
        val bgIn = if (scene.isEmpty()) 0.0 else nonPaper.count { c -> scene.any { dist(it, c) < 22f } }.toDouble() / inside.size
        val score = areaShare.pow(0.6) * pIn.pow(0.5) * minContrast.pow(1.5) * (1.0 - 0.6 * pOut) * (1.0 - bgIn).pow(5)
        return Score(score, paper, pIn, minContrast, bgIn)
    }

    /**
     * True when at least 97% of [inner] lies inside [outer]: outlines found two ways differ by a
     * little blur along the sides they share.
     */
    private fun contains(outer: List<Pt>, inner: List<Pt>): Boolean {
        var inCount = 0
        var n = 0
        for (i in 0 until 12) for (j in 0 until 12) {
            n++
            if (strictlyInside(outer, bilinear(inner, (i + 0.5) / 12, (j + 0.5) / 12))) inCount++
        }
        return inCount >= n * 0.97
    }

    /**
     * Moves each side outward while what lies just beyond it is not the scene: a coloured band
     * printed along the page's edge is page, though its outer edge is too faint to be found as a
     * line. The scene's colours are sampled well outside; the side stops where they begin.
     */
    private fun extendSides(img: Small, q: List<Pt>, w: Int, h: Int, paper: FloatArray): List<Pt>? {
        val size = sqrt(Quad.area(q))
        val cx = q.sumOf { it.x } / 4
        val cy = q.sumOf { it.y } / 4
        val offsets = DoubleArray(4)
        var changed = false
        for (k in 0 until 4) {
            val a = q[k]
            val b = q[(k + 1) % 4]
            val len = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(1.0)
            var nx = -(b.y - a.y) / len
            var ny = (b.x - a.x) / len
            if (((a.x + b.x) / 2 - cx) * nx + ((a.y + b.y) / 2 - cy) * ny < 0) { nx = -nx; ny = -ny }
            fun row(d: Double) = (0 until 24).mapNotNull { i ->
                val t = 0.1 + 0.8 * (i + 0.5) / 24
                img.at(a.x + (b.x - a.x) * t + nx * d, a.y + (b.y - a.y) * t + ny * d)
            }
            val scene = row(0.14 * size) + row(0.18 * size)
            if (scene.size < 12) continue
            fun sceneShare(d: Double): Double {
                val r = row(d)
                return if (r.size < 12) 1.0 else r.count { c -> scene.any { dist(it, c) < 22f } }.toDouble() / r.size
            }
            // Only when the strip right beyond the side is clearly not scene.
            if (sceneShare(0.008 * size) > 0.3) continue
            var d = 0.008 * size
            while (d < 0.08 * size && sceneShare(d) < 0.5) d += 0.0025 * size
            if (d >= 0.08 * size) continue // never met the scene: not a band
            offsets[k] = d
            changed = true
            trace?.invoke("extend side $k by %.3f".format(d / size))
        }
        if (!changed) return null
        // Move each side's line outward and intersect neighbours again.
        val lines = (0 until 4).map { k ->
            val a = q[k]
            val b = q[(k + 1) % 4]
            val len = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(1.0)
            var nx = -(b.y - a.y) / len
            var ny = (b.x - a.x) / len
            if (((a.x + b.x) / 2 - cx) * nx + ((a.y + b.y) / 2 - cy) * ny < 0) { nx = -nx; ny = -ny }
            Pt(a.x + nx * offsets[k], a.y + ny * offsets[k]) to Pt((b.x - a.x) / len, (b.y - a.y) / len)
        }
        fun meet(s1: Pair<Pt, Pt>, s2: Pair<Pt, Pt>): Pt? {
            val (p, u) = s1
            val (r, v) = s2
            val det = u.x * v.y - u.y * v.x
            if (abs(det) < 1e-9) return null
            val t = ((r.x - p.x) * v.y - (r.y - p.y) * v.x) / det
            return Pt(p.x + t * u.x, p.y + t * u.y)
        }
        val corners = (0 until 4).map { k -> meet(lines[(k + 3) % 4], lines[k]) ?: return null }
        return Quad.order(corners)
    }

    private fun strictlyInside(q: List<Pt>, p: Pt) = (0 until 4).all { k ->
        val a = q[k]
        val b = q[(k + 1) % 4]
        (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x) >= 0
    }

    /**
     * Whether the strip that [outer] adds to [inner] is part of the page: paper, or something that
     * stands out from the scene around [outer] (a coloured band printed on the page).
     */
    private fun stripBelongsToPage(img: Small, inner: List<Pt>, outer: List<Pt>, w: Int, h: Int, paper: FloatArray): Boolean {
        val (paperShare, matching) = stripMatch(img, inner, outer, w, h, paper) ?: return false
        trace?.invoke("grow: paper=%.2f sceneMatch=%.2f".format(paperShare, matching))
        // The strip is page when it does not look like the scene: most of its samples match none of
        // the scene's colours (a teal band on a wood desk), or it is plain paper on a dark scene.
        return matching < 0.35 || (paperShare >= 0.6 && matching < 0.6)
    }

    /**
     * The strip between [inner] and [outer]: its share of paper-like samples, and its share that
     * matches the scene just outside [outer]. Null when there is too little to judge.
     */
    private fun stripMatch(img: Small, inner: List<Pt>, outer: List<Pt>, w: Int, h: Int, paper: FloatArray): Pair<Double, Double>? {
        val strip = ArrayList<FloatArray>()
        for (i in 0 until 30) for (j in 0 until 30) {
            val p = bilinear(outer, (i + 0.5) / 30, (j + 0.5) / 30)
            if (!strictlyInside(inner, p)) img.at(p.x, p.y)?.let { strip += it }
        }
        if (strip.size < 8) return null
        // The scene just outside the larger outline (all of it, paper-coloured parts included).
        val size = sqrt(Quad.area(outer))
        val cx = outer.sumOf { it.x } / 4
        val cy = outer.sumOf { it.y } / 4
        val scene = ArrayList<FloatArray>()
        for (k in 0 until 4) for (i in 0 until 12) for (f in doubleArrayOf(0.04, 0.08)) {
            val u = (i + 0.5) / 12
            val p = when (k) { 0 -> bilinear(outer, u, 0.0); 1 -> bilinear(outer, 1.0, u); 2 -> bilinear(outer, u, 1.0); else -> bilinear(outer, 0.0, u) }
            val dx = p.x - cx
            val dy = p.y - cy
            val d = hypot(dx, dy).coerceAtLeast(1.0)
            img.at(p.x + dx / d * f * size, p.y + dy / d * f * size)?.let { scene += it }
        }
        for (i in 0 until 40) {
            val t = (i + 0.5) / 40
            for (p in listOf(Pt(t * w, 0.01 * h), Pt(t * w, 0.99 * h), Pt(0.01 * w, t * h), Pt(0.99 * w, t * h))) {
                if (!strictlyInside(outer, p)) img.at(p.x, p.y)?.let { scene += it }
            }
        }
        if (scene.isEmpty()) return null
        val matching = strip.count { c -> scene.any { dist(it, c) < 22f } }.toDouble() / strip.size
        val paperShare = strip.count { paperLike(it, paper) }.toDouble() / strip.size
        return paperShare to matching
    }
}
