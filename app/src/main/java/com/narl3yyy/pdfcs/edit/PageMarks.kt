package com.narl3yyy.pdfcs.edit

import android.content.Context
import com.narl3yyy.pdfcs.clearscan.FontFamily
import com.narl3yyy.pdfcs.clearscan.FontLibrary
import com.narl3yyy.pdfcs.clearscan.FontStyle
import com.narl3yyy.pdfcs.core.PageGeometry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import kotlin.math.atan2
import kotlin.math.hypot

/** Where a header/footer line goes. */
enum class MarkPosition(val label: String, val top: Boolean, val align: Float) {
    BOTTOM_CENTER("Bottom centre", false, 0.5f),
    BOTTOM_RIGHT("Bottom right", false, 1f),
    BOTTOM_LEFT("Bottom left", false, 0f),
    TOP_CENTER("Top centre", true, 0.5f),
    TOP_RIGHT("Top right", true, 1f),
    TOP_LEFT("Top left", true, 0f),
}

/**
 * Page marks (after pdfcraft-edit): watermarks, headers/footers with page numbers, and text
 * added onto a page. Marks are appended as their own content, with the graphics state saved and
 * restored, so the page's original content is untouched. Text uses the bundled Liberation fonts,
 * embedded as subsets.
 */
class PageMarks(context: Context) {
    private val fonts = FontLibrary.get(context)

    private fun font(doc: PDDocument, family: FontFamily = FontFamily.SANS, style: FontStyle = FontStyle.REGULAR): PDFont =
        PDType0Font.load(doc, fonts.face(family, style).file)

    private fun usable(text: String) = FontLibrary.normalize(text).filter { fonts.face(FontFamily.SANS, FontStyle.REGULAR).supports(it.code) }

    private fun geometry(p: PDPage) = PageGeometry(p.cropBox.lowerLeftX, p.cropBox.lowerLeftY, p.cropBox.width, p.cropBox.height, p.rotation)

    private fun append(doc: PDDocument, page: PDPage, block: (PDPageContentStream) -> Unit) =
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use(block)

    /** Rotation that makes text read upright on a page displayed with /Rotate. */
    private fun upright(g: PageGeometry): Float = Math.toRadians(g.rotation.toDouble()).toFloat()

    /** A diagonal, translucent watermark across every page in [pages] (all when empty). */
    fun watermark(doc: PDDocument, text: String, opacity: Float = 0.18f, argb: Int = 0xFF9E9E9E.toInt(), pages: List<Int> = emptyList()) {
        val t = usable(text).trim()
        require(t.isNotEmpty()) { "Type the watermark text" }
        val f = font(doc, FontFamily.SANS, FontStyle.BOLD)
        val gs = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = opacity.coerceIn(0.05f, 1f) }
        for (i in pages.ifEmpty { (0 until doc.numberOfPages).toList() }) {
            val page = doc.getPage(i)
            val g = geometry(page)
            val w = g.displayWidth
            val h = g.displayHeight
            val width1 = f.getStringWidth(t) / 1000f
            val size = (hypot(w, h) * 0.7f / width1).coerceIn(12f, 160f)
            val angle = atan2(h, w)
            append(doc, page) { cs ->
                cs.setGraphicsStateParameters(gs)
                cs.setNonStrokingColor(((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f)
                cs.beginText()
                cs.setFont(f, size)
                val (cx, cy) = g.toUser(0.5f, 0.5f)
                val m = Matrix.getRotateInstance((angle + upright(g)).toDouble(), cx, cy)
                m.translate(-width1 * size / 2, -size * 0.35f)
                cs.setTextMatrix(m)
                cs.showText(t)
                cs.endText()
            }
        }
    }

    /**
     * A header or footer line on every page. In [template], `{page}` is the page number and
     * `{pages}` the page count, e.g. "Page {page} of {pages}". Numbering starts at [firstNumber].
     */
    fun headerFooter(doc: PDDocument, template: String, position: MarkPosition, sizePt: Float = 10f, firstNumber: Int = 1) {
        require(template.isNotBlank()) { "Type the header or footer text" }
        val f = font(doc)
        val n = doc.numberOfPages
        for (i in 0 until n) {
            val page = doc.getPage(i)
            val g = geometry(page)
            val text = usable(template.replace("{page}", (i + firstNumber).toString()).replace("{pages}", (n + firstNumber - 1).toString()))
            if (text.isBlank()) continue
            val tw = f.getStringWidth(text) / 1000f * sizePt
            val margin = 0.5f * 72f
            val u = ((margin + position.align * (g.displayWidth - 2 * margin - tw)) / g.displayWidth).coerceIn(0f, 1f)
            val v = if (position.top) (margin + sizePt) / g.displayHeight else 1f - margin / g.displayHeight
            drawText(doc, page, g, f, text, u, v, sizePt, 0xFF333333.toInt())
        }
    }

    /** Text placed on the page with its baseline start at the display-normalised point (u, v). */
    fun addText(doc: PDDocument, pageIndex: Int, text: String, u: Float, v: Float, sizePt: Float, argb: Int, bold: Boolean = false) {
        val lines = text.lines().map { usable(it) }
        require(lines.any { it.isNotBlank() }) { "Type some text" }
        val page = doc.getPage(pageIndex)
        val f = font(doc, FontFamily.SANS, if (bold) FontStyle.BOLD else FontStyle.REGULAR)
        val g = geometry(page)
        lines.forEachIndexed { i, line ->
            if (line.isNotEmpty()) drawText(doc, page, g, f, line, u, v + i * sizePt * 1.25f / g.displayHeight, sizePt, argb)
        }
    }

    private fun drawText(doc: PDDocument, page: PDPage, g: PageGeometry, f: PDFont, text: String, u: Float, v: Float, size: Float, argb: Int) {
        val (x, y) = g.toUser(u, v)
        append(doc, page) { cs ->
            cs.setNonStrokingColor(((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f)
            cs.beginText()
            cs.setFont(f, size)
            cs.setTextMatrix(Matrix.getRotateInstance(upright(g).toDouble(), x, y))
            cs.showText(text)
            cs.endText()
        }
    }
}
