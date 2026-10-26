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
 * authors). Pure Kotlin on plain arrays so it is unit-tested on the JVM.
 */
object CameraEdges {
    /** The working size: the long side of the shrunken picture, in pixels. */
    const val WORK = 400
    private const val MAX_LINES = 24
    /** A side counts when at least this share of it lies on a matching edge. */
    private const val MIN_SUPPORT = 0.55
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
    fun detect(argb: IntArray, width: Int, height: Int): List<Pt>? {
        if (width < 16 || height < 16 || argb.size != width * height) return null
        val grey = shrink(argb, width, height)
        val k = max(width, height).toDouble() / max(grey.w, grey.h)
        val edges = edges(blur(blur(grey)))
        val lines = hough(edges)
        val quad = bestQuad(lines, edges) ?: return null
        val refined = refine(quad, edges) ?: quad
        // Back to the picture's pixels (pixel centres line up at k × (x + 0.5)).
        return refined.map { Pt((it.x + 0.5) * k, (it.y + 0.5) * k) }
    }

    private fun shrink(argb: IntArray, w: Int, h: Int): Grey {
        val step = max(1, (max(w, h) + WORK - 1) / WORK)
        val sw = (w + step - 1) / step
        val sh = (h + step - 1) / step
        val v = FloatArray(sw * sh)
        for (sy in 0 until sh) for (sx in 0 until sw) {
            var sum = 0f
            var n = 0
            for (y in sy * step until min((sy + 1) * step, h)) for (x in sx * step until min((sx + 1) * step, w)) {
                val p = argb[y * w + x]
                sum += 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
                n++
            }
            v[sy * sw + sx] = if (n > 0) sum / n else 255f
        }
        return Grey(sw, sh, v)
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

    /** Thin, strong edges: Sobel gradients, kept where they are a local maximum across the edge. */
    private fun edges(g: Grey): Edges {
        val n = g.w * g.h
        val mag = FloatArray(n)
        val dir = FloatArray(n)
        for (y in 0 until g.h) for (x in 0 until g.w) {
            fun p(dx: Int, dy: Int) = g.at(x + dx, y + dy)
            val gx = (p(1, -1) + 2 * p(1, 0) + p(1, 1)) - (p(-1, -1) + 2 * p(-1, 0) + p(-1, 1))
            val gy = (p(-1, 1) + 2 * p(0, 1) + p(1, 1)) - (p(-1, -1) + 2 * p(0, -1) + p(1, -1))
            mag[y * g.w + x] = hypot(gx, gy)
            dir[y * g.w + x] = atan2(gy, gx)
        }
        // The threshold: a share of the 98th percentile, never below a faint-edge floor.
        val sorted = mag.filter { it.isFinite() }.sorted()
        val p98 = sorted.getOrNull(sorted.size * 98 / 100) ?: 0f
        val threshold = max(0.25f * p98, 24f)
        fun at(x: Int, y: Int) = if (x < 0 || y < 0 || x >= g.w || y >= g.h) 0f else mag[y * g.w + x]
        val keep = FloatArray(n)
        for (i in 0 until n) {
            val x = i % g.w
            val y = i / g.w
            // Edges touching the border are where blurring repeats it: no information.
            val inside = x > 1 && y > 1 && x < g.w - 2 && y < g.h - 2
            if (!inside || mag[i] < threshold) continue
            val dx = cos(dir[i]).roundToInt()
            val dy = sin(dir[i]).roundToInt()
            if (mag[i] >= at(x + dx, y + dy) && mag[i] >= at(x - dx, y - dy)) keep[i] = mag[i]
        }
        return Edges(g.w, g.h, keep, dir)
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
            if (best < 0 || v <= 0f || v < 0.08f * first) break
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
     * The convex quadrilateral of two near-horizontal and two near-vertical lines whose four
     * sides all lie on edges, scored by its area times the square of its sides' mean support.
     */
    private fun bestQuad(lines: List<Line>, e: Edges): List<Pt>? {
        // A normal within 45° of vertical makes a near-horizontal line.
        fun flat(l: Line) = abs(Math.toDegrees(l.theta) - 90.0) <= 45.0
        val horizontal = lines.filter { flat(it) }
        val vertical = lines.filterNot { flat(it) }
        val fw = e.w.toDouble()
        val fh = e.h.toDouble()
        val mx = MARGIN * fw
        val my = MARGIN * fh
        var best: List<Pt>? = null
        var bestScore = -1.0
        for (i in horizontal.indices) for (i2 in i + 1 until horizontal.size) {
            for (j in vertical.indices) for (j2 in j + 1 until vertical.size) {
                val h1 = horizontal[i]; val h2 = horizontal[i2]; val v1 = vertical[j]; val v2 = vertical[j2]
                val corners = listOf(intersect(h1, v1), intersect(h1, v2), intersect(h2, v2), intersect(h2, v1))
                if (corners.any { it == null }) continue
                val q = Quad.order(corners.filterNotNull()) ?: continue
                if (q.any { it.x < -mx || it.y < -my || it.x > fw + mx || it.y > fh + my }) continue
                val area = Quad.area(q)
                if (area < MIN_AREA * fw * fh) continue
                val sides = (0 until 4).map { support(q[it], q[(it + 1) % 4], e) }
                if (sides.any { it < MIN_SUPPORT }) continue
                // Large, and with every side on its edge: a slightly larger quad with a side off
                // the sheet loses to the sheet itself.
                val mean = sides.sum() / 4
                val score = area * mean * mean
                if (score > bestScore) { bestScore = score; best = q }
            }
        }
        return best
    }
}
