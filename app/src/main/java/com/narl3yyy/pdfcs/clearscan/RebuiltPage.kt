package com.narl3yyy.pdfcs.clearscan

import android.graphics.Bitmap
import android.graphics.Rect

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

/** A picture cut from the cleaned page: a figure, photo, rule, logo, or a word kept as pixels. */
class Graphic(val rect: Rect, val bitmap: Bitmap, val photographic: Boolean)

/**
 * The result of analysing one page photo: everything needed to write it as a born-digital PDF
 * page. [pointsPerPixel] sets the physical page size.
 */
class RebuiltPage(
    val widthPx: Int,
    val heightPx: Int,
    val pointsPerPixel: Float,
    /** The page size in points; the content (widthPx × heightPx at pointsPerPixel) is centred in it. */
    val pageWidth: Float,
    val pageHeight: Float,
    val words: List<PageWord>,
    val graphics: List<Graphic>,
    val stats: Stats,
) {
    class Stats(val textWords: Int, val imageWords: Int, val verified: Int, val family: FontFamily)

    fun recycle() = graphics.forEach { it.bitmap.recycle() }
}
