package com.narl3yyy.pdfcs.camera

import com.narl3yyy.pdfcs.core.Pt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Live framing guidance while scanning: from the page outline found in each preview frame it
 * tells whether the whole page is in the picture, centred, large enough, square to the camera and
 * held still, and what to do about it. Pure logic, so it is tested without a camera.
 *
 * Corners are normalised to the preview (0..1, x right, y down) in the order top-left, top-right,
 * bottom-right, bottom-left.
 */
class LiveGuide(private val clock: () -> Long = System::currentTimeMillis) {

    enum class State { SEARCHING, ADJUST, HOLD, READY }

    /** What to show: the outline (or null), its state, and one instruction. */
    data class Advice(val corners: List<Pt>?, val state: State, val message: String, val cutOff: Boolean = false)

    private var last: List<Pt>? = null
    private var stillSince: Long? = null
    private var lostSince: Long? = null

    @Synchronized
    fun reset() {
        last = null
        stillSince = null
        lostSince = null
    }

    /** Advice for a frame where the page was found at [corners] (null: not found). */
    @Synchronized
    fun update(corners: List<Pt>?): Advice {
        val now = clock()
        if (corners == null || corners.size != 4) {
            val lost = lostSince ?: now.also { lostSince = it }
            // A frame or two without the page (motion blur) keeps the last outline on screen.
            val keep = last.takeIf { now - lost < LOST_GRACE_MS }
            if (keep == null) { last = null; stillSince = null }
            return Advice(keep, State.SEARCHING, "Point the camera at the page")
        }
        lostSince = null
        val moved = last?.let { prev -> corners.zip(prev).maxOf { (a, b) -> hypot(a.x - b.x, a.y - b.y) } } ?: 1.0
        last = corners
        val since = stillSince?.takeIf { moved <= STILL_MOVE } ?: now
        stillSince = since

        val problem = problem(corners)
        if (problem != null) {
            stillSince = now
            return Advice(corners, State.ADJUST, problem.first, problem.second)
        }
        return if (now - since >= STEADY_MS) Advice(corners, State.READY, "Ready: hold still")
        else Advice(corners, State.HOLD, "Hold still…")
    }

    companion object {
        /** A corner this close to the frame's edge (a share of it) may be cut off. */
        const val EDGE = 0.012

        /** The page should fill at least this share of the frame for small print to read well. */
        const val MIN_AREA = 0.30

        /** The page's centre may be this far from the frame's (a share of the frame). */
        const val CENTRE = 0.08

        /** Opposite sides may differ by this share before the phone is clearly tilted. */
        const val SKEW = 0.10

        /** The page may be turned this many degrees. */
        const val TURN = 6.0

        const val STILL_MOVE = 0.012
        const val STEADY_MS = 700L
        const val LOST_GRACE_MS = 600L

        /**
         * The first thing to put right, most important first, and whether part of the page is
         * outside the picture; null when the framing is good.
         */
        fun problem(c: List<Pt>): Pair<String, Boolean>? {
            val (tl, tr, br, bl) = c
            // A corner at the frame's edge: part of the page is probably outside the photo, which
            // nothing can repair afterwards.
            if (c.any { it.x < EDGE || it.y < EDGE || it.x > 1 - EDGE || it.y > 1 - EDGE }) {
                return "Move back: fit the whole page inside the frame" to true
            }
            val area = areaOf(c)
            if (area < MIN_AREA) return "Move closer to the page" to false
            val cx = c.sumOf { it.x } / 4
            val cy = c.sumOf { it.y } / 4
            // The phone moves the way the page should move on screen, the other way round.
            if (abs(cx - 0.5) > CENTRE || abs(cy - 0.5) > CENTRE) {
                val horizontal = abs(cx - 0.5) >= abs(cy - 0.5)
                val dir = if (horizontal) (if (cx < 0.5) "left" else "right") else (if (cy < 0.5) "up" else "down")
                return "Move the phone $dir to centre the page" to false
            }
            val top = dist(tl, tr)
            val bottom = dist(bl, br)
            val left = dist(tl, bl)
            val right = dist(tr, br)
            // A side shorter than its opposite is farther from the camera: the phone is tilted.
            val tiltV = (bottom - top) / max(top, bottom)
            val tiltH = (right - left) / max(left, right)
            if (abs(tiltV) > SKEW || abs(tiltH) > SKEW) {
                val edge = if (abs(tiltV) >= abs(tiltH)) (if (tiltV > 0) "top" else "bottom") else (if (tiltH > 0) "left" else "right")
                return "Hold the phone flat over the page: the $edge edge is farther away" to false
            }
            val angle = Math.toDegrees(atan2(tr.y - tl.y + br.y - bl.y, tr.x - tl.x + br.x - bl.x))
            if (abs(angle) > TURN) return "Turn the phone to line up with the page" to false
            return null
        }

        /** Area of the outline (shoelace), as a share of the frame. */
        fun areaOf(c: List<Pt>): Double {
            var s = 0.0
            for (i in c.indices) {
                val a = c[i]
                val b = c[(i + 1) % c.size]
                s += a.x * b.y - b.x * a.y
            }
            return abs(s) / 2
        }

        private fun dist(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)

        /** Corners normalised to a [w]×[h] picture. */
        fun normalised(corners: List<Pt>, w: Int, h: Int) = corners.map { Pt(it.x / w, it.y / h) }

        /** A quad as ScanPage stores it (8 numbers), from normalised corners. */
        fun toQuad(c: List<Pt>) = c.flatMap { listOf(min(1.0, max(0.0, it.x)).toFloat(), min(1.0, max(0.0, it.y)).toFloat()) }
    }
}
