package com.narl3yyy.pdfcs.clearscan

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF

/** A word on a rebuilt page. Invisible words keep the original pixels underneath them. */
class PageWord(
    val text: String,
    val face: FontFace,
    /** Font size, image pixels. */
    val size: Float,
    val scale: Float,
    val x: Float,
    val baseline: Float,
    val visible: Boolean,
    val colour: Int,
)

/**
 * A picture cut from the cleaned page ([rect]): a figure, photo, rule, logo, or a word kept as
 * pixels. It is drawn at [dest], which differs from [rect] only for a kept word moved and sized
 * with the typeset line it belongs to.
 */
class Graphic(val rect: Rect, val bitmap: Bitmap, val photographic: Boolean, var dest: RectF = RectF(rect))

/**
 * The result of analysing one page photo: everything needed to write it as a born-digital PDF
 * page, in the photo's pixels. Where those pixels go on paper is decided for the whole document
 * at once ([PageLayout]), from [measure].
 */
class RebuiltPage(
    val widthPx: Int,
    val heightPx: Int,
    val words: List<PageWord>,
    val graphics: List<Graphic>,
    val stats: Stats,
    val measure: PageLayout.Measure,
) {
    class Stats(val textWords: Int, val imageWords: Int, val verified: Int, val family: FontFamily)

    fun recycle() = graphics.forEach { it.bitmap.recycle() }
}
