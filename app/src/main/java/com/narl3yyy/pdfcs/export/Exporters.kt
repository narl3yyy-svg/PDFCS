package com.narl3yyy.pdfcs.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/** Converting a PDF to other formats (after pdfcraft-export). */
object Exporters {

    /** Plain text, pages separated by form feeds (the convention text tools understand). */
    fun text(doc: PDDocument, out: OutputStream) {
        val stripper = PDFTextStripper().apply { sortByPosition = true; pageEnd = "\n\u000C\n" }
        out.writer(Charsets.UTF_8).use { it.write(stripper.getText(doc)) }
    }

    /** Every page as a PNG at [dpi], in one ZIP. */
    fun images(pdf: File, out: OutputStream, dpi: Int = 150, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                ZipOutputStream(out).use { zip ->
                    for (i in 0 until r.pageCount) {
                        r.openPage(i).use { page ->
                            val s = dpi / 72f
                            val w = (page.width * s).roundToInt().coerceIn(1, 6000)
                            val h = (page.height * s).roundToInt().coerceIn(1, 6000)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            zip.putNextEntry(ZipEntry("page-%03d.png".format(i + 1)))
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, zip)
                            zip.closeEntry()
                            bmp.recycle()
                        }
                        onProgress(i + 1, r.pageCount)
                    }
                }
            }
        }
    }

    /**
     * A Word document (.docx) from the PDF's text: paragraphs in reading order, headings where the
     * text is clearly larger than the body, bold where the font is bold, a page break per page.
     */
    fun docx(doc: PDDocument, out: OutputStream) {
        val paragraphs = Paragraphs.read(doc)
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>""")
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            entry("word/_rels/document.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            entry("word/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/><w:sz w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="240"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="200"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:sz w:val="26"/></w:rPr></w:style></w:styles>""")
            val body = StringBuilder()
            var lastPage = 0
            for (p in paragraphs) {
                if (p.page != lastPage) { body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>"); lastPage = p.page }
                body.append("<w:p>")
                p.heading?.let { body.append("<w:pPr><w:pStyle w:val=\"Heading$it\"/></w:pPr>") }
                for (run in p.runs) {
                    body.append("<w:r>")
                    if (run.bold) body.append("<w:rPr><w:b/></w:rPr>")
                    body.append("<w:t xml:space=\"preserve\">").append(escape(run.text)).append("</w:t></w:r>")
                }
                body.append("</w:p>")
            }
            entry("word/document.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="567" w:footer="567" w:gutter="0"/></w:sectPr></w:body></w:document>""")
        }
    }

    private fun escape(s: String) = buildString {
        for (c in s) when {
            c == '&' -> append("&amp;")
            c == '<' -> append("&lt;")
            c == '>' -> append("&gt;")
            c == '"' -> append("&quot;")
            // XML 1.0 forbids most control characters.
            c < ' ' && c != '\t' && c != '\n' && c != '\r' -> Unit
            else -> append(c)
        }
    }
}

/** Paragraphs recovered from a PDF's text, with headings and bold runs. */
internal object Paragraphs {
    class Run(val text: String, val bold: Boolean)
    class Paragraph(val page: Int, val runs: List<Run>, val heading: Int?)

    fun read(doc: PDDocument): List<Paragraph> {
        class Line(val page: Int, val text: StringBuilder = StringBuilder(), val runs: MutableList<Run> = ArrayList(), var size: Float = 0f, var y: Float = 0f, var gap: Float = 0f)
        val lines = ArrayList<Line>()
        val stripper = object : PDFTextStripper() {
            var cur: Line? = null
            override fun writeString(text: String, positions: MutableList<TextPosition>) {
                val first = positions.firstOrNull() ?: return
                val line = cur ?: Line(currentPageNo).also { cur = it; lines += it; it.y = first.yDirAdj }
                val bold = first.font?.name?.contains("Bold", ignoreCase = true) == true
                val last = line.runs.lastOrNull()
                if (last != null && last.bold == bold) line.runs[line.runs.lastIndex] = Run(last.text + text, bold) else line.runs += Run(text, bold)
                line.text.append(text)
                line.size = maxOf(line.size, positions.maxOf { it.fontSizeInPt })
            }
            override fun writeWordSeparator() {
                val line = cur ?: return
                val last = line.runs.lastOrNull() ?: return
                line.runs[line.runs.lastIndex] = Run(last.text + " ", last.bold)
            }
            override fun writeLineSeparator() { cur = null }
            override fun writeParagraphEnd() { cur = null }
            override fun writePageEnd() { cur = null }
        }
        stripper.sortByPosition = true
        stripper.getText(doc)
        if (lines.isEmpty()) return emptyList()
        // Body size: the most common line size.
        val body = lines.groupingBy { (it.size * 2).roundToInt() / 2f }.eachCount().maxByOrNull { it.value }?.key ?: 11f
        val out = ArrayList<Paragraph>()
        var runs = ArrayList<Run>()
        var page = lines.first().page
        var prev: Line? = null
        fun flush(heading: Int? = null) {
            if (runs.isNotEmpty() && runs.any { it.text.isNotBlank() }) out += Paragraph(page, runs, heading)
            runs = ArrayList()
        }
        for (l in lines) {
            val heading = when {
                l.size >= body * 1.6f -> 1
                l.size >= body * 1.2f -> 2
                else -> null
            }
            val p = prev
            val newParagraph = p == null || p.page != l.page || heading != null ||
                (l.y - p.y) > maxOf(l.size, p.size) * 1.7f || p.text.trimEnd().endsWith(":")
            if (heading != null) {
                flush(); page = l.page
                runs.addAll(l.runs); flush(heading); prev = null; continue
            }
            if (newParagraph) { flush(); page = l.page }
            else if (runs.isNotEmpty()) {
                // Join wrapped lines; a hyphen at the end of a line joins the word.
                val last = runs.last()
                runs[runs.lastIndex] = if (last.text.endsWith("-") && last.text.length > 1 && last.text[last.text.length - 2].isLetter())
                    Run(last.text.dropLast(1), last.bold) else Run(last.text.trimEnd() + " ", last.bold)
            }
            runs.addAll(l.runs)
            prev = l
        }
        flush()
        return out
    }
}
