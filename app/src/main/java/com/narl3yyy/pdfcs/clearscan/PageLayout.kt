package com.narl3yyy.pdfcs.clearscan

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lays out a whole rebuilt document once every page has been read, the way a typeset document
 * is: one paper size, body text at one size, the text block at the same place on every page.
 *
 * A photo cropped to the sheet is the page itself: its outline at the paper size puts everything
 * where the printer put it. Such pages ("true crops") teach the document's body size and margins.
 * A page whose crop is wrong (cut into the text, the desk left in, the wrong shape) is set from
 * those instead: its body text at the document's size, its text block at the document's margins.
 */
object PageLayout {
    /** What a rebuilt page measured, in its own pixels. [content] is left, top, right, bottom. */
    class Measure(
        val widthPx: Int,
        val heightPx: Int,
        val content: IntArray?,
        /** Body text size in pixels, or null when the page has too little body text to tell. */
        val bodyPx: Float?,
    )

    /** Where a page's pixels go: [scale] points per pixel, the image's top-left at ([originX], [originTop]). */
    data class Placed(val paperW: Float, val paperH: Float, val scale: Float, val originX: Float, val originTop: Float)

    /** Paper and common book trim sizes in points (portrait): Letter, A4, Legal, A5, B5, 6×9", 5.5×8.5", 5×8". */
    val STANDARD_SIZES = listOf(
        612f to 792f, 595.28f to 841.89f, 612f to 1008f, 419.53f to 595.28f, 498.9f to 708.66f,
        432f to 648f, 396f to 612f, 360f to 576f,
    )

    /** Body text size when no page shows it (a document of figures). */
    const val DEFAULT_BODY_POINTS = 10f

    /** The narrowest margin a page gets, in points (a quarter inch). */
    const val MIN_MARGIN = 18f

    /** Margins of a page set without a true crop when no page taught better: three quarters of an inch. */
    const val DEFAULT_MARGIN = 54f

    fun layout(pages: List<Measure>): List<Placed> {
        if (pages.isEmpty()) return emptyList()
        val paper = paperFor(pages)
        // True crops: the sheet's own shape, with paper left around the text on every side.
        val fits = pages.map { m -> fitScale(m, paper) }
        val trueCrop = pages.mapIndexed { i, m -> fits[i] != null && !tight(m) }
        // The document's body size, from the true crops with body text.
        val bodies = pages.indices.filter { trueCrop[it] && pages[it].bodyPx != null }.map { pages[it].bodyPx!! * fits[it]!!.scale }
        val body = median(bodies) ?: DEFAULT_BODY_POINTS
        // A true crop whose body text is far off the rest is a crop with desk in it (or cut short).
        val good = pages.indices.map { i ->
            trueCrop[i] && pages[i].bodyPx.let { b -> b == null || bodies.size < 3 || abs(b * fits[i]!!.scale / body - 1f) < 0.12f }
        }
        // The text block of the good pages: median margins.
        val lefts = ArrayList<Float>(); val tops = ArrayList<Float>(); val rights = ArrayList<Float>()
        for (i in pages.indices) {
            if (!good[i]) continue
            val c = pages[i].content ?: continue
            val f = fits[i]!!
            lefts += f.originX + c[0] * f.scale
            tops += f.originTop + c[1] * f.scale
            rights += f.paperW - (f.originX + c[2] * f.scale)
        }
        val left = median(lefts) ?: DEFAULT_MARGIN
        val top = median(tops) ?: DEFAULT_MARGIN
        val right = median(rights) ?: DEFAULT_MARGIN
        return pages.mapIndexed { i, m -> if (good[i]) fits[i]!! else placeByText(m, paper, body, left, top, right) }
    }

    /**
     * The photo as the sheet itself, on paper of its shape (portrait, or turned for a sheet shot
     * landscape); null when its shape is not the paper's.
     */
    private fun fitScale(m: Measure, paper: Pair<Float, Float>): Placed? {
        val aspect = m.heightPx.toFloat() / m.widthPx
        for ((pw, ph) in listOf(paper, paper.second to paper.first)) {
            if (abs(aspect / (ph / pw) - 1f) > ASPECT_TOLERANCE) continue
            val s = min(pw / m.widthPx, ph / m.heightPx)
            return Placed(pw, ph, s, (pw - m.widthPx * s) / 2, (ph - m.heightPx * s) / 2)
        }
        return null
    }

    /**
     * A page without a true crop: body text at the document's size (a page without body text is
     * fitted to the text block, never enlarged beyond its photo as a page), its content where
     * the document's text block starts; narrow content (a title, a figure) centred across it.
     */
    private fun placeByText(m: Measure, paper: Pair<Float, Float>, body: Float, left: Float, top: Float, right: Float): Placed {
        val c = m.content ?: intArrayOf(0, 0, m.widthPx, m.heightPx)
        val cw = max(1, c[2] - c[0]).toFloat()
        val ch = max(1, c[3] - c[1]).toFloat()
        fun blockWidth(pw: Float) = (pw - left - right).coerceAtLeast(pw * 0.5f)
        fun scaleOn(pw: Float, ph: Float): Float {
            val s = m.bodyPx?.let { body / it } ?: min(blockWidth(pw) / cw, min(pw / m.widthPx, ph / m.heightPx))
            return min(s, min((pw - 2 * MIN_MARGIN) / cw, (ph - 2 * MIN_MARGIN) / ch))
        }
        // Portrait like the rest of the document, unless its text would have to shrink to fit (a
        // wide table shot landscape) and turned paper keeps it clearly larger.
        var (pw, ph) = paper
        var s = scaleOn(pw, ph)
        if (m.bodyPx != null && cw > ch && scaleOn(ph, pw) > s * 1.15f) { pw = paper.second; ph = paper.first; s = scaleOn(pw, ph) }
        val blockW = blockWidth(pw)
        val blockL = (pw - blockW) * left / (left + right).coerceAtLeast(1f)
        val w = cw * s
        val h = ch * s
        val x = when {
            w > blockW -> (pw - w) / 2
            w < blockW * 0.7f -> blockL + (blockW - w) / 2
            else -> blockL
        }
        // Content that is short of the page keeps the document's top margin; tall content is moved
        // up just enough to fit.
        val y = min(top, ph - MIN_MARGIN - h).coerceAtLeast(MIN_MARGIN)
        return Placed(pw, ph, s, x - c[0] * s, y - c[1] * s)
    }

    /** Text reaching the photo's edge: the crop cut into the page, so its outline is not the sheet's. */
    private fun tight(m: Measure): Boolean {
        val c = m.content ?: return false
        val mx = m.widthPx * 0.015f
        val my = m.heightPx * 0.012f
        return c[0] < mx || c[1] < my || m.widthPx - c[2] < mx || m.heightPx - c[3] < my
    }

    /**
     * The document's paper: the standard size most photos have the shape of (a vote, so a few bad
     * crops don't decide it); with no clear shape, the size whose body text would come out nearest
     * to [DEFAULT_BODY_POINTS] for the median photo.
     */
    fun paperFor(pages: List<Measure>): Pair<Float, Float> {
        val votes = HashMap<Pair<Float, Float>, Int>()
        for (m in pages) {
            val aspect = max(m.widthPx, m.heightPx).toFloat() / min(m.widthPx, m.heightPx)
            STANDARD_SIZES.filter { (w, h) -> abs(aspect / (h / w) - 1f) <= ASPECT_TOLERANCE }
                .minByOrNull { (w, h) -> abs(aspect / (h / w) - 1f) }
                ?.let { votes[it] = (votes[it] ?: 0) + 1 }
        }
        votes.maxWithOrNull(compareBy<Map.Entry<Pair<Float, Float>, Int>> { it.value }.thenBy { -STANDARD_SIZES.indexOf(it.key) })
            ?.let { return it.key }
        val withBody = pages.filter { it.bodyPx != null }
        if (withBody.isEmpty()) return STANDARD_SIZES[0]
        val shortSidePts = median(withBody.map { min(it.widthPx, it.heightPx) * DEFAULT_BODY_POINTS / it.bodyPx!! })!!
        return STANDARD_SIZES.take(2).minBy { abs(it.first - shortSidePts) }
    }

    private fun median(v: List<Float>): Float? = v.sorted().let { it.getOrNull(it.size / 2) }

    /** How far a photo's shape may be from the paper's and still be the sheet itself. */
    private const val ASPECT_TOLERANCE = 0.035f
}
