package com.narl3yyy.pdfcs.clearscan

import android.graphics.Bitmap
import com.googlecode.leptonica.android.ReadFile
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessPdfRenderer
import com.narl3yyy.pdfcs.ocr.OcrEngine
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
}
