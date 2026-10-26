package com.narl3yyy.pdfcs.inspect

import com.narl3yyy.pdfcs.annot.Annotations
import com.narl3yyy.pdfcs.crypt.Protection
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.text.DateFormat
import java.util.Calendar

/** Editable document properties. */
data class Metadata(val title: String, val author: String, val subject: String, val keywords: String)

/** What "Analyze" reports about a document. */
data class Report(
    val fileSize: Long,
    val pdfVersion: Float,
    val pages: Int,
    val pageSizes: List<String>,
    val metadata: Metadata,
    val creator: String?,
    val producer: String?,
    val created: String?,
    val modified: String?,
    val security: String,
    val fonts: List<FontUse>,
    val images: Int,
    val words: Int,
    val pagesWithText: Int,
    val comments: Int,
    val formFields: Int,
    val bookmarks: Boolean,
    val tagged: Boolean,
) {
    data class FontUse(val name: String, val embedded: Boolean)

    /** Pages that are pictures only: no text to search until OCR runs on them. */
    val pagesWithoutText get() = pages - pagesWithText

    val suggestions: List<String> get() = buildList {
        if (pagesWithoutText > 0) add("$pagesWithoutText of $pages pages have no searchable text. Use “Make searchable (OCR)” to read them.")
        if (fonts.any { !it.embedded }) add("Some fonts are not embedded, so the document may look different on other devices.")
        if (metadata.title.isBlank()) add("The document has no title. Add one in Properties so it is easy to find.")
        if (!tagged) add("The document is not tagged, which makes it harder to read with a screen reader.")
    }
}

/** Document inspection (after PdfCraft's doc_info / doc_inspect tools). */
object DocumentInfo {
    fun metadata(doc: PDDocument): Metadata {
        val i = doc.documentInformation
        return Metadata(i.title.orEmpty(), i.author.orEmpty(), i.subject.orEmpty(), i.keywords.orEmpty())
    }

    fun setMetadata(doc: PDDocument, m: Metadata) {
        val i = doc.documentInformation ?: PDDocumentInformation()
        i.title = m.title.ifBlank { null }
        i.author = m.author.ifBlank { null }
        i.subject = m.subject.ifBlank { null }
        i.keywords = m.keywords.ifBlank { null }
        i.modificationDate = Calendar.getInstance()
        doc.documentInformation = i
    }

    fun report(doc: PDDocument, file: File): Report {
        val info = doc.documentInformation
        val fonts = LinkedHashMap<String, Boolean>()
        var images = 0
        val seen = HashSet<Any>()
        fun scan(res: PDResources?, depth: Int) {
            if (res == null || depth > 8) return
            for (n in res.fontNames) runCatching { res.getFont(n) }.getOrNull()?.let { f ->
                val name = f.name?.substringAfter('+') ?: "Unnamed font"
                fonts[name] = (fonts[name] ?: true) && f.isEmbedded
            }
            for (n in res.xObjectNames) {
                val x = runCatching { res.getXObject(n) }.getOrNull() ?: continue
                if (!seen.add(x.cosObject)) continue
                when (x) {
                    is PDImageXObject -> images++
                    is PDFormXObject -> scan(x.resources, depth + 1)
                }
            }
        }
        var withText = 0
        var words = 0
        val stripper = PDFTextStripper()
        for (i in 0 until doc.numberOfPages) {
            scan(doc.getPage(i).resources, 0)
            stripper.startPage = i + 1
            stripper.endPage = i + 1
            val text = runCatching { stripper.getText(doc) }.getOrDefault("")
            val n = text.split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }
            if (n > 0) withText++
            words += n
        }
        val sizes = (0 until doc.numberOfPages).map { p -> doc.getPage(p).mediaBox.let { describeSize(it.width, it.height) } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { (s, n) -> if (n > 1) "$s × $n" else s }
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        return Report(
            fileSize = file.length(),
            pdfVersion = doc.version,
            pages = doc.numberOfPages,
            pageSizes = sizes,
            metadata = metadata(doc),
            creator = info.creator,
            producer = info.producer,
            created = info.creationDate?.time?.let(date::format),
            modified = info.modificationDate?.time?.let(date::format),
            security = Protection.describe(doc),
            fonts = fonts.map { (n, e) -> Report.FontUse(n, e) },
            images = images,
            words = words,
            pagesWithText = withText,
            comments = runCatching { Annotations.list(doc).size }.getOrDefault(0),
            formFields = doc.documentCatalog.acroForm?.fieldTree?.count() ?: 0,
            bookmarks = doc.documentCatalog.documentOutline?.firstChild != null,
            tagged = doc.documentCatalog.markInfo?.isMarked == true,
        )
    }

    /** Names common paper sizes; anything else in millimetres. */
    fun describeSize(wPt: Float, hPt: Float): String {
        val w = wPt / 72f * 25.4f
        val h = hPt / 72f * 25.4f
        val (a, b) = if (w <= h) w to h else h to w
        val name = PAPER.entries.firstOrNull { (_, s) -> kotlin.math.abs(s.first - a) < 3 && kotlin.math.abs(s.second - b) < 3 }?.key
        val mm = "%.0f × %.0f mm".format(w, h)
        return if (name != null) "$name ($mm)" else mm
    }

    private val PAPER = linkedMapOf(
        "A4" to (210f to 297f), "Letter" to (215.9f to 279.4f), "A5" to (148f to 210f), "Legal" to (215.9f to 355.6f),
        "A3" to (297f to 420f), "B5" to (176f to 250f), "Executive" to (184.2f to 266.7f),
    )
}
