package com.narl3yyy.pdfcs.core

import kotlin.math.atan2
import kotlin.math.max

/** A point in pixels (x right, y down). */
data class Pt(val x: Double, val y: Double)

/** Quadrilateral helpers (after pdfcraft-geom's perspective module). */
object Quad {
    /**
     * Orders four points as top-left, top-right, bottom-right, bottom-left, or returns null when
     * they don't make a convex, non-degenerate quadrilateral.
     */
    fun order(points: List<Pt>): List<Pt>? {
        if (points.size != 4 || points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        val cx = points.sumOf { it.x } / 4
        val cy = points.sumOf { it.y } / 4
        // Ascending angle about the centre is clockwise on screen (y down).
        val sorted = points.sortedBy { atan2(it.y - cy, it.x - cx) }
        val first = sorted.indices.minBy { sorted[it].x + sorted[it].y }
        val q = List(4) { sorted[(first + it) % 4] }
        fun cross(o: Pt, a: Pt, b: Pt) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val turns = List(4) { cross(q[it], q[(it + 1) % 4], q[(it + 2) % 4]) }
        val area = turns.sum() / 2
        val size = max(q.maxOf { it.x } - q.minOf { it.x }, q.maxOf { it.y } - q.minOf { it.y })
        val convex = turns.all { it > 1e-9 * size * size }
        return if (convex && area > 1e-6 * size * size) q else null
    }

    /** Shoelace area of an ordered polygon. */
    fun area(q: List<Pt>): Double =
        kotlin.math.abs(q.indices.sumOf { val p = q[it]; val n = q[(it + 1) % q.size]; p.x * n.y - n.x * p.y }) / 2
}
