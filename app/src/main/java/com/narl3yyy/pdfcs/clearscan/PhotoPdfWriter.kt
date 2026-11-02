package com.narl3yyy.pdfcs.clearscan

import android.graphics.Bitmap
import com.googlecode.leptonica.android.ReadFile
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessPdfRenderer
import com.narl3yyy.pdfcs.ocr.OcrEngine
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix
import java.io.Closeable
import java.io.File

/**
 * "Original photos" output: Tesseract's own PDF renderer writes each page photo with an
 * invisible, exactly positioned text layer on top ("searchable image"). Works for every OCR
 * language, including scripts the bundled fonts cannot typeset.
 *
 * OCR runs on [add]'s `ocrImage`, while the PDF shows the (smaller) JPEG in `displayJpeg`;
 * Tesseract scales that picture to the page size the OCR image defines.
 */
class PhotoPdfWriter(private val ocr: OcrEngine) : Closeable {
    private var renderer: TessPdfRenderer? = null

    /** [outputBase] is the target path without the ".pdf" extension, which Tesseract adds. */
    fun begin(outputBase: File, title: String) {
        ocr.api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        ocr.api.setVariable("textonly_pdf", "0")
        val r = TessPdfRenderer(ocr.api, outputBase.path)
        renderer = r
        check(ocr.api.beginDocument(r, title)) { "Could not create the PDF" }
    }

    fun add(ocrImage: Bitmap, displayJpeg: File): Boolean {
        val r = checkNotNull(renderer) { "begin() first" }
        val pix = ReadFile.readBitmap(ocrImage) ?: return false
        return try {
            ocr.api.addPageToDocument(pix, displayJpeg.path, r)
        } finally {
            pix.recycle()
        }
    }

    fun end() {
        val r = renderer ?: return
        ocr.api.endDocument(r)
        r.recycle()
        renderer = null
    }

    override fun close() {
        renderer?.recycle()
        renderer = null
    }

    companion object {
        /**
         * Puts every page of a finished [pdf] on the document's paper ([PageLayout]): Tesseract
         * makes each page the size of its photo, so pages cropped a little differently would all
         * differ in size. Rewrites the file in place.
         */
        fun onPaper(pdf: File) {
            val tmp = File(pdf.path + ".paper")
            PDDocument.load(pdf, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                // Photo pixels at Tesseract's resolution: one point is one 72nd of an inch.
                val k = OcrEngine.DPI / 72f
                val measures = doc.pages.map { p ->
                    PageLayout.Measure((p.mediaBox.width * k).toInt().coerceAtLeast(1), (p.mediaBox.height * k).toInt().coerceAtLeast(1), null, null)
                }
                val placed = PageLayout.layout(measures)
                for ((i, page) in doc.pages.withIndex()) {
                    val p = placed[i]
                    val s = p.scale * k
                    val box = page.mediaBox
                    page.mediaBox = PDRectangle(p.paperW, p.paperH)
                    page.cropBox = null
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.PREPEND, true).use { cs ->
                        cs.saveGraphicsState()
                        cs.transform(Matrix(s, 0f, 0f, s, p.originX - box.lowerLeftX * s, p.paperH - p.originTop - box.upperRightY * s))
                    }
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true).use { it.restoreGraphicsState() }
                }
                doc.save(tmp)
            }
            if (!tmp.renameTo(pdf)) { tmp.copyTo(pdf, overwrite = true); tmp.delete() }
        }
    }
}
