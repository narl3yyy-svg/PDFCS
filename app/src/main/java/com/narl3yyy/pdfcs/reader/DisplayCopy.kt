package com.narl3yyy.pdfcs.reader

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The file the reader actually renders. Android's PdfRenderer (before Android 15) draws no
 * annotations at all, so highlights, notes, drawings and filled-in form fields would be
 * invisible. For documents that have any, the reader renders a copy in which each annotation's
 * appearance stream is drawn into the page content (ISO 32000-2 §12.5.5, the appearance's BBox
 * mapped onto the annotation's Rect). The document itself keeps its annotations untouched.
 * Protected documents are decrypted into the same copy.
 */
object DisplayCopy {
    suspend fun prepare(file: File, cacheDir: File, password: String?): File = withContext(Dispatchers.IO) {
        val key = "${file.canonicalPath.hashCode()}_${file.length()}_${file.lastModified()}"
        val dir = File(cacheDir, "display").apply { mkdirs() }
        val out = File(dir, "$key.pdf")
        if (out.exists()) return@withContext out
        val prefix = "${file.canonicalPath.hashCode()}_"
        dir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
        PDDocument.load(file, password ?: "", MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val encrypted = doc.isEncrypted
            var drawn = 0
            for (page in doc.pages) {
                val annotations = runCatching { page.annotations }.getOrDefault(emptyList())
                val visible = annotations.filter { shown(it) }
                if (visible.isEmpty()) continue
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (a in visible) if (draw(cs, a)) drawn++
                }
                // Links stay (they draw nothing); the rest are now part of the page.
                page.annotations = annotations.filter { it.subtype == "Link" }
            }
            if (drawn == 0 && !encrypted) return@withContext file
            if (encrypted) doc.isAllSecurityToBeRemoved = true
            doc.save(out)
        }
        out
    }

    private fun shown(a: PDAnnotation) =
        a.subtype != "Link" && a.subtype != "Popup" && !a.isHidden && !a.isNoView && a.appearance?.normalAppearance != null

    /** Draws the annotation's normal appearance at its rectangle. */
    private fun draw(cs: PDPageContentStream, a: PDAnnotation): Boolean {
        val ap = runCatching { a.normalAppearanceStream }.getOrNull() ?: return false
        val rect: PDRectangle = a.rectangle ?: return false
        val bbox = ap.bBox ?: return false
        // The appearance's BBox after its own Matrix, as an upright box.
        val m = ap.matrix
        val corners = listOf(bbox.lowerLeftX to bbox.lowerLeftY, bbox.upperRightX to bbox.lowerLeftY,
            bbox.upperRightX to bbox.upperRightY, bbox.lowerLeftX to bbox.upperRightY).map { (x, y) -> m.transformPoint(x, y) }
        val x0 = corners.minOf { it.x }; val x1 = corners.maxOf { it.x }
        val y0 = corners.minOf { it.y }; val y1 = corners.maxOf { it.y }
        if (x1 - x0 <= 0f || y1 - y0 <= 0f || rect.width <= 0f || rect.height <= 0f) return false
        val sx = rect.width / (x1 - x0)
        val sy = rect.height / (y1 - y0)
        cs.saveGraphicsState()
        cs.transform(Matrix(sx, 0f, 0f, sy, rect.lowerLeftX - x0 * sx, rect.lowerLeftY - y0 * sy))
        cs.drawForm(ap)
        cs.restoreGraphicsState()
        return true
    }
}
