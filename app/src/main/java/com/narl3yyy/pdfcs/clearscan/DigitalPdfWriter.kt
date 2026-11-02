package com.narl3yyy.pdfcs.clearscan

import android.graphics.Color
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import java.io.Closeable
import java.io.File
import java.util.Calendar

/**
 * Writes [RebuiltPage]s as a born-digital PDF: pictures first, then every word as real text in
 * an embedded (subset) font, visible or invisible (rendering mode 3) over pixels kept as they were.
 *
 * Each page is written in its photo's pixels as it comes; the paper size and where the pixels go
 * on it are decided for the whole document when it is saved ([PageLayout]), and set then.
 */
class DigitalPdfWriter : Closeable {
    private val doc = PDDocument(MemoryUsageSetting.setupTempFileOnly())
    private val pdfFonts = HashMap<FontFace, PDFont>()
    private val measures = ArrayList<PageLayout.Measure>()

    private fun font(face: FontFace) = pdfFonts.getOrPut(face) { PDType0Font.load(doc, face.file) }

    fun add(page: RebuiltPage) {
        val pdPage = PDPage(PDRectangle(page.widthPx.toFloat(), page.heightPx.toFloat()))
        doc.addPage(pdPage)
        measures += page.measure
        // Image y runs down from the image's top edge; PDF y runs up.
        val top = page.heightPx.toFloat()
        PDPageContentStream(doc, pdPage).use { cs ->
            for (g in page.graphics) {
                // Opaque pictures: without this every image gets a needless transparency mask.
                g.bitmap.setHasAlpha(false)
                val img = if (g.photographic) JPEGFactory.createFromImage(doc, g.bitmap, 0.85f)
                else LosslessFactory.createFromImage(doc, g.bitmap)
                cs.drawImage(img, g.dest.left, top - g.dest.bottom, g.dest.width(), g.dest.height())
            }
            for (w in page.words) {
                val text = w.text.filter { w.face.supports(it.code) }
                if (text.isBlank()) continue
                cs.beginText()
                cs.setFont(font(w.face), w.size)
                cs.setRenderingMode(if (w.visible) RenderingMode.FILL else RenderingMode.NEITHER)
                cs.setNonStrokingColor(Color.red(w.colour) / 255f, Color.green(w.colour) / 255f, Color.blue(w.colour) / 255f)
                cs.setHorizontalScaling(w.scale * 100f)
                cs.newLineAtOffset(w.x, top - w.baseline)
                cs.showText(text)
                cs.endText()
            }
        }
    }

    /** Lays out every page on the document's paper, then saves. */
    fun save(out: File, title: String) {
        val placed = PageLayout.layout(measures)
        for ((i, p) in placed.withIndex()) {
            val page = doc.getPage(i)
            val m = measures[i]
            page.mediaBox = PDRectangle(p.paperW, p.paperH)
            // Pixel (x, y-up) to points: scaled, and the image's top-left at (originX, originTop).
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.PREPEND, true).use { cs ->
                cs.saveGraphicsState()
                cs.transform(Matrix(p.scale, 0f, 0f, p.scale, p.originX, p.paperH - p.originTop - m.heightPx * p.scale))
            }
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true).use { it.restoreGraphicsState() }
        }
        doc.documentInformation = PDDocumentInformation().apply {
            this.title = title
            creator = "PDFCS"
            producer = "PDFCS (Tesseract OCR, PdfBox)"
            creationDate = Calendar.getInstance()
        }
        doc.save(out)
    }

    override fun close() = doc.close()
}
