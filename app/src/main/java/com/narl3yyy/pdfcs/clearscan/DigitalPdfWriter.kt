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
import java.io.Closeable
import java.io.File
import java.util.Calendar

/**
 * Writes [RebuiltPage]s as a born-digital PDF: pictures first, then every word as real text in
 * an embedded (subset) font, visible or invisible (rendering mode 3) over pixels kept as they were.
 */
class DigitalPdfWriter : Closeable {
    private val doc = PDDocument(MemoryUsageSetting.setupTempFileOnly())
    private val pdfFonts = HashMap<FontFace, PDFont>()

    private fun font(face: FontFace) = pdfFonts.getOrPut(face) { PDType0Font.load(doc, face.file) }

    fun add(page: RebuiltPage) {
        val s = page.pointsPerPixel
        val pdPage = PDPage(PDRectangle(page.pageWidth, page.pageHeight))
        doc.addPage(pdPage)
        // Content is centred: image y runs down from the top of the content box.
        val ox = (page.pageWidth - page.widthPx * s) / 2
        val top = page.pageHeight - (page.pageHeight - page.heightPx * s) / 2
        PDPageContentStream(doc, pdPage).use { cs ->
            for (g in page.graphics) {
                // Opaque pictures: without this every image gets a needless transparency mask.
                g.bitmap.setHasAlpha(false)
                val img = if (g.photographic) JPEGFactory.createFromImage(doc, g.bitmap, 0.85f)
                else LosslessFactory.createFromImage(doc, g.bitmap)
                cs.drawImage(img, ox + g.rect.left * s, top - g.rect.bottom * s, g.rect.width() * s, g.rect.height() * s)
            }
            for (w in page.words) {
                val text = w.text.filter { w.face.supports(it.code) }
                if (text.isBlank()) continue
                cs.beginText()
                cs.setFont(font(w.face), w.size * s)
                cs.setRenderingMode(if (w.visible) RenderingMode.FILL else RenderingMode.NEITHER)
                cs.setNonStrokingColor(Color.red(w.colour) / 255f, Color.green(w.colour) / 255f, Color.blue(w.colour) / 255f)
                cs.setHorizontalScaling(w.scale * 100f)
                cs.newLineAtOffset(ox + w.x * s, top - w.baseline * s)
                cs.showText(text)
                cs.endText()
            }
        }
    }

    fun save(out: File, title: String) {
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
