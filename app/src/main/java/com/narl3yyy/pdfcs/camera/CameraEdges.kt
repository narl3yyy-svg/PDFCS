package com.narl3yyy.pdfcs.camera

import com.narl3yyy.pdfcs.core.Pt
import com.narl3yyy.pdfcs.core.Quad
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds a photographed page's corners: the edges of the sheet are the four strongest straight
 * lines that enclose a large convex area, each backed by an edge along most of its length.
 * Returns null when nothing convinces, so the caller falls back to the whole photo or to corners
 * the user places.
 *
 * Steps: shrink the picture to [WORK] pixels on its long side, blur it, take Sobel gradients and
 * keep thin, strong edges; vote for lines (a Hough transform that only votes along each edge's
 * own gradient direction, give or take a few degrees); pair two near-horizontal with two
 * near-vertical lines; keep the quadrilaterals whose four sides lie on edges, choose the largest
 * and refine each side with a least-squares fit.
 *
 * Ported from PdfCraft's `engine/src/camera_edges.rs` (MIT OR Apache-2.0, © the PdfCraft
 * authors) and adapted: gradients come from every colour channel, and it proposes several
 * candidates for [PageFinder] to verify instead of choosing one itself. Pure Kotlin on plain
 * arrays so it is unit-tested on the JVM.
 */
object CameraEdges {
    /** The working size: the long side of the shrunken picture, in pixels. */
    const val WORK = 400
    private const val MAX_LINES = 30
    private const val MAX_CANDIDATES = 12
    /** A side counts when at least this share of it lies on a matching edge. */
    private const val MIN_SUPPORT = 0.5
    /** A document fills at least this share of the picture. */
    private const val MIN_AREA = 0.12
    /** How far (share of the picture's size) a corner may lie outside the picture. */
    private const val MARGIN = 0.02

    private class Grey(val w: Int, val h: Int, val v: FloatArray) {
        fun at(x: Int, y: Int): Float = v[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
    }

    /** Gradient strength (0 = not an edge) and direction (radians, the edge's normal). */
    private class Edges(val w: Int, val h: Int, val mag: FloatArray, val dir: FloatArray) {
        fun isEdge(i: Int) = mag[i] > 0f
    }

    private class Line(val theta: Double, val rho: Double)

    /**
     * The page corners in an ARGB picture of [width] × [height], in its pixels: top-left,
     * top-right, bottom-right, bottom-left. Null when no quadrilateral convinces.
     */
    fun detect(argb: IntArray, width: Int, height: Int): List<Pt>? = candidates(argb, width, height).firstOrNull()?.corners

    /** A possible page outline and how well its four sides lie on edges (0..1). */
    class Candidate(val corners: List<Pt>, val support: Double)

    /**
     * The best page outlines, most convincing first (at most [MAX_CANDIDATES]), in picture pixels.
     * [PageFinder] checks them against what a page looks like and picks one.
     */
    fun candidates(argb: IntArray, width: Int, height: Int): List<Candidate> {
        if (width < 16 || height < 16 || argb.size != width * height) return emptyList()
        val channels = shrink(argb, width, height)
        val k = max(width, height).toDouble() / max(channels[0].w, channels[0].h)
        val edges = edges(channels.map { blur(blur(it)) })
        val lines = hough(edges)
        return quads(lines, edges).map { (q, support) ->
            val refined = refine(q, edges) ?: q
            // Back to the picture's pixels (pixel centres line up at k × (x + 0.5)).
            Candidate(refined.map { Pt((it.x + 0.5) * k, (it.y + 0.5) * k) }, support)
        }
    }

    /**
     * Snaps an outline (picture pixels, TL TR BR BL) onto the photo's edges: for each side, the
     * parallel line within a band of ±[band] (share of the picture) that most edge points support,
     * then a least-squares fit through those points. Null when a side finds no support.
     */
    fun snap(argb: IntArray, width: Int, height: Int, corners: List<Pt>, band: Double = 0.03): List<Pt>? {
        if (width < 16 || height < 16 || argb.size != width * height) return null
        val channels = shrink(argb, width, height)
        val k = max(width, height).toDouble() / max(channels[0].w, channels[0].h)
        val e = edges(channels.map { blur(blur(it)) })
        val q = corners.map { Pt(it.x / k - 0.5, it.y / k - 0.5) }
        val reach = band * max(e.w, e.h)
        val sides = ArrayList<Pair<Pt, Pt>>(4)
        for (s in 0 until 4) {
            val a = q[s]
            val b = q[(s + 1) % 4]
            val len = hypot(b.x - a.x, b.y - a.y)
            if (len < 8.0) return null
            val ux = (b.x - a.x) / len
            val uy = (b.y - a.y) / len
            val normal = atan2(ux, -uy).toFloat()
            // Signed offsets (across the side) of edge points that face across it, in 1 px bins.
            val bins = IntArray((2 * reach).toInt() + 3)
            val pts = ArrayList<Triple<Double, Double, Double>>() // x, y, offset
            val x0 = (min(a.x, b.x) - reach).toInt().coerceAtLeast(0)
            val x1 = (max(a.x, b.x) + reach).toInt().coerceAtMost(e.w - 1)
            val y0 = (min(a.y, b.y) - reach).toInt().coerceAtLeast(0)
            val y1 = (max(a.y, b.y) + reach).toInt().coerceAtMost(e.h - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val i = y * e.w + x
                if (!e.isEdge(i) || !facing(e.dir[i], normal)) continue
                val dx = x - a.x
                val dy = y - a.y
                val t = (dx * ux + dy * uy) / len
                val off = -dx * uy + dy * ux
                if (t < 0.08 || t > 0.92 || abs(off) > reach) continue
                pts += Triple(x.toDouble(), y.toDouble(), off)
                bins[(off + reach).roundToInt().coerceIn(0, bins.lastIndex)]++
            }
            // Well-supported offsets (3-bin windows); of those, the one nearest the outline, so a
            // strong edge just inside the page (a printed band) does not pull the side in.
            val window = IntArray(bins.size) { i -> if (i == 0 || i == bins.lastIndex) 0 else bins[i - 1] + bins[i] + bins[i + 1] }
            val strongest = window.maxOrNull() ?: 0
            if (strongest < len * 0.35) return null
            var bestBin = -1
            for (i in 1 until bins.size - 1) {
                val isPeak = window[i] >= strongest * 0.6 && window[i] >= window[i - 1] && window[i] >= window[i + 1]
                if (isPeak && (bestBin < 0 || abs(i - reach) < abs(bestBin - reach))) bestBin = i
            }
            if (bestBin < 0) return null
            val centre = bestBin - reach
            val near = pts.filter { abs(it.third - centre) <= 2.0 }
            if (near.size < 10) return null
            val cx = near.sumOf { it.first } / near.size
            val cy = near.sumOf { it.second } / near.size
            var sxx = 0.0; var sxy = 0.0; var syy = 0.0
            for (p in near) { val dx = p.first - cx; val dy = p.second - cy; sxx += dx * dx; sxy += dx * dy; syy += dy * dy }
            val angle = 0.5 * atan2(2 * sxy, sxx - syy)
            sides += Pt(cx, cy) to Pt(cos(angle), sin(angle))
        }
        fun meet(s1: Pair<Pt, Pt>, s2: Pair<Pt, Pt>): Pt? {
            val (p, u) = s1
            val (r, v) = s2
            val det = u.x * v.y - u.y * v.x
            if (abs(det) < 1e-6) return null
            val t = ((r.x - p.x) * v.y - (r.y - p.y) * v.x) / det
            return Pt(p.x + t * u.x, p.y + t * u.y).takeIf { it.x.isFinite() && it.y.isFinite() }
        }
        val snapped = (0 until 4).map { c -> meet(sides[(c + 3) % 4], sides[c]) ?: return null }
        val ordered = Quad.order(snapped) ?: return null
        return ordered.map { Pt((it.x + 0.5) * k, (it.y + 0.5) * k) }
    }

    /** Box-filters the picture down to at most [WORK] pixels on its long side, one grey per colour channel. */
    private fun shrink(argb: IntArray, w: Int, h: Int): List<Grey> {
        val step = max(1, (max(w, h) + WORK - 1) / WORK)
        val sw = (w + step - 1) / step
        val sh = (h + step - 1) / step
        val ch = List(3) { FloatArray(sw * sh) }
        for (sy in 0 until sh) for (sx in 0 until sw) {
            var r = 0f; var g = 0f; var b = 0f
            var n = 0
            for (y in sy * step until min((sy + 1) * step, h)) for (x in sx * step until min((sx + 1) * step, w)) {
                val p = argb[y * w + x]
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
                n++
            }
            val i = sy * sw + sx
            if (n > 0) { ch[0][i] = r / n; ch[1][i] = g / n; ch[2][i] = b / n } else { ch[0][i] = 255f; ch[1][i] = 255f; ch[2][i] = 255f }
        }
        return ch.map { Grey(sw, sh, it) }
    }

    /** A 3×3 binomial blur. */
    private fun blur(g: Grey): Grey {
        val v = FloatArray(g.v.size)
        val wts = floatArrayOf(1f, 2f, 1f)
        for (y in 0 until g.h) for (x in 0 until g.w) {
            var s = 0f
            for (dy in -1..1) for (dx in -1..1) s += wts[dy + 1] * wts[dx + 1] * g.at(x + dx, y + dy)
            v[y * g.w + x] = s / 16f
        }
        return Grey(g.w, g.h, v)
    }

    /**
     * Thin, strong edges: Sobel gradients of each colour channel (the strongest channel counts, so a
     * teal band on brown wood is an edge although both are equally bright), kept where they are a
     * local maximum across the edge.
     */
    private fun edges(channels: List<Grey>): Edges {
        val g0 = channels[0]
        val n = g0.w * g0.h
        val mag = FloatArray(n)
        val dir = FloatArray(n)
        for (y in 0 until g0.h) for (x in 0 until g0.w) {
            var best = -1f
            for (g in channels) {
                fun p(dx: Int, dy: Int) = g.at(x + dx, y + dy)
                val gx = (p(1, -1) + 2 * p(1, 0) + p(1, 1)) - (p(-1, -1) + 2 * p(-1, 0) + p(-1, 1))
                val gy = (p(-1, 1) + 2 * p(0, 1) + p(1, 1)) - (p(-1, -1) + 2 * p(0, -1) + p(1, -1))
                val m = hypot(gx, gy)
                if (m > best) { best = m; dir[y * g0.w + x] = atan2(gy, gx) }
            }
            mag[y * g0.w + x] = best
        }
        // The threshold: a share of the 98th percentile, never below a faint-edge floor. Kept low:
        // a white page on a light desk has a faint edge, and every candidate is verified later.
        val sorted = mag.filter { it.isFinite() }.sorted()
        val p98 = sorted.getOrNull(sorted.size * 98 / 100) ?: 0f
        val threshold = max(0.15f * p98, 14f)
        fun at(x: Int, y: Int) = if (x < 0 || y < 0 || x >= g0.w || y >= g0.h) 0f else mag[y * g0.w + x]
        val keep = FloatArray(n)
        for (i in 0 until n) {
            val x = i % g0.w
            val y = i / g0.w
            // Edges touching the border are where blurring repeats it: no information.
            val inside = x > 1 && y > 1 && x < g0.w - 2 && y < g0.h - 2
            if (!inside || mag[i] < threshold) continue
            val dx = cos(dir[i]).roundToInt()
            val dy = sin(dir[i]).roundToInt()
            if (mag[i] >= at(x + dx, y + dy) && mag[i] >= at(x - dx, y - dy)) keep[i] = mag[i]
        }
        return Edges(g0.w, g0.h, keep, dir)
    }

    /**
     * The strongest lines through the edges: each edge votes for the lines within 6° of its own
     * normal, weighted by its strength; peaks are taken strongest first, clearing their area.
     */
    private fun hough(e: Edges): List<Line> {
        val steps = 180
        val diag = ceil(sqrt((e.w * e.w + e.h * e.h).toDouble())).toInt()
        val rows = 2 * diag + 1
        val acc = FloatArray(steps * rows)
        val cs = DoubleArray(steps) { cos(it * PI / 180) }
        val sn = DoubleArray(steps) { sin(it * PI / 180) }
        for (i in e.mag.indices) {
            if (!e.isEdge(i)) continue
            val x = (i % e.w).toDouble()
            val y = (i / e.w).toDouble()
            val normal = Math.floorMod(Math.toDegrees(e.dir[i].toDouble()).roundToInt(), 180)
            // ±6°: a jagged (aliased) edge's local gradient leans towards the axes.
            for (t0 in normal - 6..normal + 6) {
                val t = Math.floorMod(t0, steps)
                val r = (x * cs[t] + y * sn[t]).roundToInt() + diag
                if (r in 0 until rows) acc[t * rows + r] += e.mag[i]
            }
        }
        val lines = ArrayList<Line>()
        val first = acc.maxOrNull() ?: 0f
        while (lines.size < MAX_LINES) {
            var best = -1
            var v = 0f
            for (i in acc.indices) if (acc[i] > v) { v = acc[i]; best = i }
            if (best < 0 || v <= 0f || v < 0.04f * first) break
            val t = best / rows
            val r = best % rows
            lines += Line(t * PI / 180, (r - diag).toDouble())
            // Clear ±6° and ±6 px around the peak (θ wraps, flipping ρ's sign).
            for (dt in -6..6) {
                var tt = t + dt
                var flip = false
                if (tt < 0) { tt += steps; flip = true } else if (tt >= steps) { tt -= steps; flip = true }
                val rr = if (flip) 2 * diag - r else r
                for (dr in -6..6) {
                    val q = rr + dr
                    if (q in 0 until rows) acc[tt * rows + q] = 0f
                }
            }
        }
        return lines
    }

    private fun intersect(a: Line, b: Line): Pt? {
        val c1 = cos(a.theta); val s1 = sin(a.theta); val c2 = cos(b.theta); val s2 = sin(b.theta)
        val det = c1 * s2 - s1 * c2
        if (abs(det) < 1e-6) return null
        val p = Pt((a.rho * s2 - b.rho * s1) / det, (c1 * b.rho - c2 * a.rho) / det)
        return if (p.x.isFinite() && p.y.isFinite()) p else null
    }

    /** True when the edge's direction lies across the side whose normal is [normal]. */
    private fun facing(dir: Float, normal: Float): Boolean {
        val d = ((dir - normal) % PI.toFloat() + PI.toFloat()) % PI.toFloat()
        return !d.isNaN() && (d < 0.35f || d > PI.toFloat() - 0.35f)
    }

    /** The share of the side from [a] to [b] that lies on an edge facing across it. */
    private fun support(a: Pt, b: Pt, e: Edges): Double {
        val len = hypot(b.x - a.x, b.y - a.y)
        if (len < 1.0) return 0.0
        val ux = (b.x - a.x) / len
        val uy = (b.y - a.y) / len
        val normal = atan2(ux, -uy).toFloat()
        val n = (len / 2).coerceIn(8.0, 200.0).toInt()
        var hits = 0
        for (i in 0 until n) {
            val t = 0.05 + 0.9 * (i + 0.5) / n
            val px = a.x + (b.x - a.x) * t
            val py = a.y + (b.y - a.y) * t
            val hit = (-2..2).any { o ->
                val x = (px - uy * o).roundToInt()
                val y = (py + ux * o).roundToInt()
                if (x < 0 || y < 0 || x >= e.w || y >= e.h) false
                else { val i2 = y * e.w + x; e.isEdge(i2) && facing(e.dir[i2], normal) }
            }
            if (hit) hits++
        }
        return hits.toDouble() / n
    }

    /**
     * Sharpen the quadrilateral found on the coarse line grid: fit each side's line through the
     * edge points along it (total least squares), then intersect neighbouring sides again.
     */
    private fun refine(q: List<Pt>, e: Edges): List<Pt>? {
        val sides = ArrayList<Pair<Pt, Pt>>(4) // (point on it, unit direction)
        for (k in 0 until 4) {
            val a = q[k]
            val b = q[(k + 1) % 4]
            val len = hypot(b.x - a.x, b.y - a.y)
            if (len < 4.0) return null
            val ux = (b.x - a.x) / len
            val uy = (b.y - a.y) / len
            val normal = atan2(ux, -uy).toFloat()
            var sx = 0.0; var sy = 0.0; var n = 0
            val pts = ArrayList<Pt>()
            val x0 = (min(a.x, b.x).toInt() - 3).coerceAtLeast(0)
            val x1 = (ceil(max(a.x, b.x)).toInt() + 3).coerceAtMost(e.w - 1)
            val y0 = (min(a.y, b.y).toInt() - 3).coerceAtLeast(0)
            val y1 = (ceil(max(a.y, b.y)).toInt() + 3).coerceAtMost(e.h - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val i = y * e.w + x
                if (!e.isEdge(i) || !facing(e.dir[i], normal)) continue
                val dx = x - a.x
                val dy = y - a.y
                val t = (dx * ux + dy * uy) / len
                val off = -dx * uy + dy * ux
                if (t in 0.05..0.95 && abs(off) <= 3.0) { pts += Pt(x.toDouble(), y.toDouble()); sx += x; sy += y; n++ }
            }
            if (n < 10) return null
            val cx = sx / n
            val cy = sy / n
            var sxx = 0.0; var sxy = 0.0; var syy = 0.0
            for (p in pts) { val dx = p.x - cx; val dy = p.y - cy; sxx += dx * dx; sxy += dx * dy; syy += dy * dy }
            val angle = 0.5 * atan2(2 * sxy, sxx - syy)
            sides += Pt(cx, cy) to Pt(cos(angle), sin(angle))
        }
        fun meet(s1: Pair<Pt, Pt>, s2: Pair<Pt, Pt>): Pt? {
            val (p, u) = s1
            val (r, v) = s2
            val det = u.x * v.y - u.y * v.x
            if (abs(det) < 1e-6) return null
            val t = ((r.x - p.x) * v.y - (r.y - p.y) * v.x) / det
            return Pt(p.x + t * u.x, p.y + t * u.y).takeIf { it.x.isFinite() && it.y.isFinite() }
        }
        // Corner k is where side k-1 (ending there) meets side k (starting there).
        val refined = (0 until 4).map { k -> meet(sides[(k + 3) % 4], sides[k]) ?: return null }
        // A fit that moved a corner far is not a refinement.
        if (refined.zip(q).any { (r, o) -> hypot(r.x - o.x, r.y - o.y) > 6.0 }) return null
        return Quad.order(refined)
    }

    /**
     * Convex quadrilaterals of two near-horizontal and two near-vertical lines whose four sides all
     * lie on edges, scored by area times the square of their sides' mean support; the best
     * [MAX_CANDIDATES] that differ from each other, best first, with their mean support.
     */
    private fun quads(lines: List<Line>, e: Edges): List<Pair<List<Pt>, Double>> {
        // A normal within 45° of vertical makes a near-horizontal line.
        fun flat(l: Line) = abs(Math.toDegrees(l.theta) - 90.0) <= 45.0
        val horizontal = lines.filter { flat(it) }
        val vertical = lines.filterNot { flat(it) }
        val fw = e.w.toDouble()
        val fh = e.h.toDouble()
        val mx = MARGIN * fw
        val my = MARGIN * fh
        val found = ArrayList<Triple<List<Pt>, Double, Double>>() // quad, score, support
        for (i in horizontal.indices) for (i2 in i + 1 until horizontal.size) {
            for (j in vertical.indices) for (j2 in j + 1 until vertical.size) {
                val h1 = horizontal[i]; val h2 = horizontal[i2]; val v1 = vertical[j]; val v2 = vertical[j2]
                val corners = listOf(intersect(h1, v1), intersect(h1, v2), intersect(h2, v2), intersect(h2, v1))
                if (corners.any { it == null }) continue
                val q = Quad.order(corners.filterNotNull()) ?: continue
                if (q.any { it.x < -mx || it.y < -my || it.x > fw + mx || it.y > fh + my }) continue
                val area = Quad.area(q)
                if (area < MIN_AREA * fw * fh) continue
                var total = 0.0
                var ok = true
                for (k in 0 until 4) {
                    val sup = support(q[k], q[(k + 1) % 4], e)
                    if (sup < MIN_SUPPORT) { ok = false; break }
                    total += sup
                }
                if (!ok) continue
                val mean = total / 4
                found += Triple(q, area * mean * mean, mean)
            }
        }
        // Keep distinct shapes: a corner more than 2% of the picture away from every kept one.
        val out = ArrayList<Pair<List<Pt>, Double>>()
        val tol = 0.02 * max(fw, fh)
        for ((q, _, sup) in found.sortedByDescending { it.second }) {
            if (out.any { (o, _) -> o.zip(q).all { (a, b) -> hypot(a.x - b.x, a.y - b.y) < tol } }) continue
            out += q to sup
            if (out.size >= MAX_CANDIDATES) break
        }
        return out
    }
}
