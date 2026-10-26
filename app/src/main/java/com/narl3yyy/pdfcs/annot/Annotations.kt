package com.narl3yyy.pdfcs.annot

import android.graphics.RectF
import com.narl3yyy.pdfcs.core.PageGeometry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import java.util.Calendar

/** Text markup kinds (ISO 32000-2 §12.5.6.10). */
enum class Markup(val label: String, val subtype: String, val defaultColour: Int) {
    HIGHLIGHT("Highlight", PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT, 0xFFFFD54F.toInt()),
    UNDERLINE("Underline", PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE, 0xFF1E88E5.toInt()),
    STRIKEOUT("Strike out", PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT, 0xFFE53935.toInt()),
}

/** A comment as listed in the Comments panel. */
data class CommentInfo(val page: Int, val index: Int, val kind: String, val text: String?, val author: String?)

/**
 * Comments (after pdfcraft-annot): text markup over selected text, sticky notes, freehand ink.
 * Every annotation gets an appearance stream, so any viewer shows it the same way. Positions
 * come in display-normalised page coordinates (0..1, top-left) and are mapped to user space.
 */
object Annotations {
    const val AUTHOR = "PDFCS"

    private fun geometry(page: PDPage): PageGeometry {
        val box = page.cropBox
        return PageGeometry(box.lowerLeftX, box.lowerLeftY, box.width, box.height, page.rotation)
    }

    private fun colour(argb: Int) = PDColor(
        floatArrayOf(((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f),
        PDDeviceRGB.INSTANCE,
    )

    private fun stamp(a: PDAnnotationMarkup, contents: String?) {
        a.titlePopup = AUTHOR
        a.contents = contents
        a.creationDate = Calendar.getInstance()
        a.setModifiedDate(Calendar.getInstance())
        a.isPrinted = true
    }

    /**
     * Adds a highlight, underline or strike-out over [rects] (one per text line, display-normalised).
     * [quoted] is the marked text, kept as the comment's contents.
     */
    fun addMarkup(doc: PDDocument, pageIndex: Int, kind: Markup, rects: List<RectF>, quoted: String?, argb: Int = kind.defaultColour) {
        require(rects.isNotEmpty()) { "Select some text first" }
        val page = doc.getPage(pageIndex)
        val g = geometry(page)
        val quads = FloatArray(rects.size * 8)
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        rects.forEachIndexed { i, r ->
            // QuadPoints order (de-facto, as Acrobat writes it): upper-left, upper-right, lower-left, lower-right.
            val (ulx, uly) = g.toUser(r.left, r.top)
            val (urx, ury) = g.toUser(r.right, r.top)
            val (llx, lly) = g.toUser(r.left, r.bottom)
            val (lrx, lry) = g.toUser(r.right, r.bottom)
            floatArrayOf(ulx, uly, urx, ury, llx, lly, lrx, lry).copyInto(quads, i * 8)
            x0 = minOf(x0, ulx, urx, llx, lrx); x1 = maxOf(x1, ulx, urx, llx, lrx)
            y0 = minOf(y0, uly, ury, lly, lry); y1 = maxOf(y1, uly, ury, lly, lry)
        }
        val a = PDAnnotationTextMarkup(kind.subtype)
        a.rectangle = PDRectangle(x0, y0, x1 - x0, y1 - y0)
        a.quadPoints = quads
        a.color = colour(argb)
        if (kind == Markup.HIGHLIGHT) a.constantOpacity = 0.45f
        stamp(a, quoted)
        page.annotations = page.annotations + a
        a.constructAppearances(doc)
    }

    /** Adds a sticky note at a display-normalised point. */
    fun addNote(doc: PDDocument, pageIndex: Int, u: Float, v: Float, text: String) {
        val page = doc.getPage(pageIndex)
        val (x, y) = geometry(page).toUser(u, v)
        val a = PDAnnotationText()
        a.rectangle = PDRectangle(x, y - 20f, 20f, 20f)
        a.name = PDAnnotationText.NAME_COMMENT
        a.color = colour(0xFFFFC107.toInt())
        stamp(a, text)
        page.annotations = page.annotations + a
        a.constructAppearances(doc)
    }

    /** Adds freehand ink: each stroke a list of display-normalised (u, v) points. */
    fun addInk(doc: PDDocument, pageIndex: Int, strokes: List<List<Pair<Float, Float>>>, argb: Int, widthPt: Float) {
        val usable = strokes.filter { it.size >= 2 }
        require(usable.isNotEmpty()) { "Draw something first" }
        val page = doc.getPage(pageIndex)
        val g = geometry(page)
        val ink = usable.map { s -> s.flatMap { (u, v) -> g.toUser(u, v).toList() }.toFloatArray() }
        val xs = ink.flatMap { s -> s.filterIndexed { i, _ -> i % 2 == 0 } }
        val ys = ink.flatMap { s -> s.filterIndexed { i, _ -> i % 2 == 1 } }
        val pad = widthPt
        val a = PDAnnotationMarkup()
        a.cosObject.setName(com.tom_roush.pdfbox.cos.COSName.SUBTYPE, PDAnnotationMarkup.SUB_TYPE_INK)
        a.rectangle = PDRectangle(xs.min() - pad, ys.min() - pad, xs.max() - xs.min() + 2 * pad, ys.max() - ys.min() + 2 * pad)
        a.inkList = ink.toTypedArray()
        a.color = colour(argb)
        if ((argb ushr 24) < 255) a.constantOpacity = (argb ushr 24) / 255f
        a.borderStyle = PDBorderStyleDictionary().apply { width = widthPt }
        stamp(a, null)
        page.annotations = page.annotations + a
        a.constructAppearances(doc)
    }

    /** The comments in the document (links and form widgets are not comments). */
    fun list(doc: PDDocument): List<CommentInfo> = (0 until doc.numberOfPages).flatMap { p ->
        doc.getPage(p).annotations.mapIndexedNotNull { i, a ->
            if (a.subtype in NOT_COMMENTS) null
            else CommentInfo(p, i, label(a), a.contents, (a as? PDAnnotationMarkup)?.titlePopup)
        }
    }

    fun delete(doc: PDDocument, page: Int, index: Int) {
        val p = doc.getPage(page)
        val list = p.annotations.toMutableList()
        require(index in list.indices) { "That comment no longer exists" }
        val removed = list.removeAt(index)
        // A markup's pop-up goes with it.
        (removed as? PDAnnotationMarkup)?.popup?.let { popup -> list.removeAll { it.cosObject == popup.cosObject } }
        p.annotations = list
    }

    private val NOT_COMMENTS = setOf("Link", "Widget", "Popup")

    private fun label(a: PDAnnotation) = when (a.subtype) {
        PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT -> "Highlight"
        PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE -> "Underline"
        PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT -> "Strike-out"
        PDAnnotationTextMarkup.SUB_TYPE_SQUIGGLY -> "Squiggly"
        "Text" -> "Note"
        PDAnnotationMarkup.SUB_TYPE_INK -> "Drawing"
        "FreeText" -> "Text box"
        else -> a.subtype ?: "Comment"
    }
}
