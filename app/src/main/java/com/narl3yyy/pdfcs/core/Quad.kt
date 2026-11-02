package com.narl3yyy.pdfcs.core

import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

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

    /**
     * Width over height of the real rectangle photographed as [q] (TL, TR, BR, BL, pixels) in a
     * [w]×[h] photo, undoing perspective (Zhang & He, "Whiteboard scanning and image
     * enhancement", 2007): the camera's focal length is estimated from the four corners, with the
     * optical centre in the middle of the photo. Averaging side lengths instead squashes a page
     * shot at a slant. When the corners give no usable focal length (a slant too slight to measure
     * one, or noise), a phone's typical one ([TYPICAL_FOCAL] × the long side) is used. Null when the
     * corners are degenerate.
     */
    fun aspect(q: List<Pt>, w: Int, h: Int): Double? {
        if (q.size != 4) return null
        val cx = w / 2.0
        val cy = h / 2.0
        // Homogeneous corners about the optical centre: m1 TL, m2 TR, m3 BL, m4 BR.
        fun v(p: Pt) = doubleArrayOf(p.x - cx, p.y - cy, 1.0)
        val m1 = v(q[0]); val m2 = v(q[1]); val m3 = v(q[3]); val m4 = v(q[2])
        fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val d2 = dot(cross(m2, m4), m3)
        val d3 = dot(cross(m3, m4), m2)
        if (kotlin.math.abs(d2) < 1e-9 || kotlin.math.abs(d3) < 1e-9) return null
        val k2 = dot(cross(m1, m4), m3) / d2
        val k3 = dot(cross(m1, m4), m2) / d3
        val n2 = DoubleArray(3) { k2 * m2[it] - m1[it] }
        val n3 = DoubleArray(3) { k3 * m3[it] - m1[it] }
        val long = max(w, h).toDouble()
        val nn = n2[2] * n3[2]
        val measured = if (kotlin.math.abs(nn) > 1e-9) -(n2[0] * n3[0] + n2[1] * n3[1]) / nn else -1.0
        // A measured focal length is trusted only within what phone cameras have.
        val f2 = measured.takeIf { it > 0 && sqrt(it) in (0.45 * long)..(2.5 * long) } ?: (TYPICAL_FOCAL * long).let { it * it }
        val a2 = (n2[0] * n2[0] + n2[1] * n2[1]) / f2 + n2[2] * n2[2]
        val a3 = (n3[0] * n3[0] + n3[1] * n3[1]) / f2 + n3[2] * n3[2]
        if (a2 <= 0 || a3 <= 0) return null
        return sqrt(a2 / a3).takeIf { it.isFinite() && it > 0.05 && it < 20 }
    }

    /** A phone's main camera: focal length about 0.75 × the photo's long side (a 26 mm equivalent lens). */
    const val TYPICAL_FOCAL = 0.75
}
