package com.narl3yyy.pdfcs.core

/**
 * Maps between a page as it is displayed (normalised 0..1, origin top-left, after the page's
 * /Rotate) and PDF user space (points, origin bottom-left of the crop box, before rotation).
 * ISO 32000-2 §7.7.3.3: /Rotate turns the page clockwise for display.
 */
class PageGeometry(
    private val llx: Float,
    private val lly: Float,
    private val width: Float,
    private val height: Float,
    rotation: Int,
) {
    val rotation: Int = ((rotation % 360) + 360) % 360

    /** Display size in points (width and height swap for quarter turns). */
    val displayWidth get() = if (rotation == 90 || rotation == 270) height else width
    val displayHeight get() = if (rotation == 90 || rotation == 270) width else height

    /** Display-normalised (u, v) → user-space (x, y). */
    fun toUser(u: Float, v: Float): Pair<Float, Float> = when (rotation) {
        90 -> llx + v * width to lly + u * height
        180 -> llx + (1 - u) * width to lly + v * height
        270 -> llx + (1 - v) * width to lly + (1 - u) * height
        else -> llx + u * width to lly + (1 - v) * height
    }

    /** User-space (x, y) → display-normalised (u, v). */
    fun toDisplay(x: Float, y: Float): Pair<Float, Float> {
        val a = (x - llx) / width
        val b = (y - lly) / height
        return when (rotation) {
            90 -> b to a
            180 -> (1 - a) to b
            270 -> (1 - b) to (1 - a)
            else -> a to (1 - b)
        }
    }

    /** A display-normalised rectangle as a user-space rectangle [x0, y0, x1, y1]. */
    fun rectToUser(left: Float, top: Float, right: Float, bottom: Float): FloatArray {
        val (ax, ay) = toUser(left, top)
        val (bx, by) = toUser(right, bottom)
        return floatArrayOf(minOf(ax, bx), minOf(ay, by), maxOf(ax, bx), maxOf(ay, by))
    }
}
