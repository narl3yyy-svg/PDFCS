package com.narl3yyy.pdfcs.reader

import android.graphics.RectF
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.text.Normalizer
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * Text of one page plus, for each character, its box in page coordinates
 * normalised to 0..1 (origin top-left). Whitespace we insert has no box (NaN).
 */
class PageText(val text: String, val boxes: FloatArray)

data class SearchHit(val page: Int, val rects: List<RectF>, val snippet: String)

/**
 * Full-text index of a PDF, built from its text layer (for our scans that is
 * Tesseract's invisible OCR text). Cached on disk so big books index once.
 */
class PdfTextIndex(val pages: List<PageText>) {
    private val folded = arrayOfNulls<Pair<String, IntArray>>(pages.size)

    fun search(query: String, limit: Int = 5000): List<SearchHit> {
        val q = fold(query).trim().replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<SearchHit>()
        pages.forEachIndexed { pageIndex, page ->
            val (hay, map) = folded[pageIndex] ?: foldWithMap(page.text).also { folded[pageIndex] = it }
            var from = 0
            while (hits.size < limit) {
                val at = hay.indexOf(q, from)
                if (at < 0) break
                val start = map[at]
                val end = map[at + q.length - 1]
                hits += SearchHit(pageIndex, rectsFor(page, start, end), snippet(page.text, start, end))
                from = at + max(1, q.length)
            }
        }
        return hits
    }

    /** The character nearest to the display-normalised point, preferring the line under it. */
    fun charNear(pageIndex: Int, u: Float, v: Float): Int? {
        val page = pages.getOrNull(pageIndex) ?: return null
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in 0 until page.text.length) {
            val b = i * 4
            if (b + 3 >= page.boxes.size || page.boxes[b].isNaN()) continue
            val l = page.boxes[b]; val t = page.boxes[b + 1]; val r = page.boxes[b + 2]; val bt = page.boxes[b + 3]
            val dx = if (u < l) l - u else if (u > r) u - r else 0f
            val dy = if (v < t) t - v else if (v > bt) v - bt else 0f
            // Vertical distance counts more: stay on the line the finger is on.
            val d = dx + dy * 4f
            if (d < bestD) { bestD = d; best = i }
        }
        return best.takeIf { it >= 0 && bestD < 0.08f }
    }

    /** The word containing character [i]: the run of non-space characters around it. */
    fun wordAround(pageIndex: Int, i: Int): IntRange {
        val text = pages.getOrNull(pageIndex)?.text ?: return IntRange.EMPTY
        if (i !in text.indices) return IntRange.EMPTY
        var a = i
        var b = i
        while (a > 0 && !text[a - 1].isWhitespace()) a--
        while (b < text.lastIndex && !text[b + 1].isWhitespace()) b++
        return a..b
    }

    fun text(pageIndex: Int, range: IntRange): String {
        val text = pages.getOrNull(pageIndex)?.text ?: return ""
        val a = range.first.coerceIn(0, text.length)
        val b = (range.last + 1).coerceIn(a, text.length)
        return text.substring(a, b).replace(Regex("[ \t]*\n[ \t]*"), "\n").trim()
    }

    /** One rectangle per text line covering the characters in [range]. */
    fun rects(pageIndex: Int, range: IntRange): List<RectF> {
        val page = pages.getOrNull(pageIndex) ?: return emptyList()
        if (range.isEmpty()) return emptyList()
        return rectsFor(page, range.first, range.last)
    }

    private fun rectsFor(page: PageText, start: Int, end: Int): List<RectF> {
        val out = ArrayList<RectF>()
        var cur: RectF? = null
        for (i in start..end) {
            val b = i * 4
            if (b + 3 >= page.boxes.size) break
            val l = page.boxes[b]
            if (l.isNaN()) continue
            val t = page.boxes[b + 1]
            val r = page.boxes[b + 2]
            val bt = page.boxes[b + 3]
            val c = cur
            // Same line if the vertical extents overlap substantially.
            if (c != null && min(c.bottom, bt) - max(c.top, t) > 0.4f * min(c.height(), bt - t)) {
                c.union(l, t, r, bt)
            } else {
                cur = RectF(l, t, r, bt).also { out += it }
            }
        }
        return out
    }

    private fun snippet(text: String, start: Int, end: Int): String {
        val s = max(0, start - 40)
        val e = min(text.length, end + 50)
        val body = text.substring(s, e).replace(Regex("\\s+"), " ").trim()
        return (if (s > 0) "…" else "") + body + (if (e < text.length) "…" else "")
    }

    companion object {
        private const val CACHE_VERSION = 2

        /** Case- and accent-insensitive form used for matching. */
        private fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

        /**
         * Folds [text] character by character, collapsing whitespace runs and
         * joining words hyphenated across line breaks, and remembers which
         * original index each folded character came from.
         */
        private fun foldWithMap(text: String): Pair<String, IntArray> {
            val sb = StringBuilder(text.length)
            val map = IntArray(text.length * 3 + 1)
            var lastSpace = true
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                if (ch == '-' && i + 1 < text.length && text[i + 1] == '\n' && i > 0 && text[i - 1].isLetter()) {
                    i += 2
                    continue
                }
                if (ch.isWhitespace()) {
                    if (!lastSpace) { map[sb.length] = i; sb.append(' ') }
                    lastSpace = true
                } else {
                    if (ch.code < 128) {
                        map[sb.length] = i
                        sb.append(ch.lowercaseChar())
                    } else {
                        for (c in fold(ch.toString()).take(3)) { map[sb.length] = i; sb.append(c) }
                    }
                    lastSpace = false
                }
                i++
            }
            return sb.toString() to map.copyOf(max(1, sb.length))
        }

        suspend fun load(file: File, cacheDir: File, onProgress: (Int, Int) -> Unit): PdfTextIndex =
            withContext(Dispatchers.IO) {
                val cache = File(cacheDir, "index/${file.name.hashCode()}_${file.length()}_${file.lastModified()}.idx")
                readCache(cache)?.let { return@withContext it }
                val index = build(file, onProgress)
                runCatching { writeCache(cache, index) }
                index
            }

        private suspend fun build(file: File, onProgress: (Int, Int) -> Unit): PdfTextIndex {
            val pages = ArrayList<PageText>()
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                val total = doc.numberOfPages
                for (p in 1..total) {
                    coroutineContext.ensureActive()
                    pages += extractPage(doc, p)
                    onProgress(p, total)
                }
            }
            return PdfTextIndex(pages)
        }

        private fun extractPage(doc: PDDocument, pageNumber: Int): PageText {
            val sb = StringBuilder()
            val boxes = ArrayList<Float>()
            fun gap(s: String) {
                sb.append(s)
                repeat(s.length) { boxes.add(Float.NaN); boxes.add(Float.NaN); boxes.add(Float.NaN); boxes.add(Float.NaN) }
            }
            val stripper = object : PDFTextStripper() {
                override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                    for (tp in textPositions) {
                        val w = tp.pageWidth.takeIf { it > 0 } ?: 1f
                        val h = tp.pageHeight.takeIf { it > 0 } ?: 1f
                        val glyphH = max(tp.heightDir, tp.fontSizeInPt * 0.7f).coerceAtLeast(1f)
                        val left = tp.xDirAdj / w
                        val right = (tp.xDirAdj + max(tp.widthDirAdj, 0.5f)) / w
                        val top = (tp.yDirAdj - glyphH) / h
                        val bottom = (tp.yDirAdj + glyphH * 0.22f) / h
                        for (c in tp.unicode ?: continue) {
                            sb.append(c)
                            boxes.add(left); boxes.add(top); boxes.add(right); boxes.add(bottom)
                        }
                    }
                }

                override fun writeWordSeparator() = gap(" ")
                override fun writeLineSeparator() = gap("\n")
                override fun writeParagraphEnd() = gap("\n")
                override fun writePageEnd() = Unit
            }
            stripper.sortByPosition = true
            stripper.startPage = pageNumber
            stripper.endPage = pageNumber
            runCatching { stripper.getText(doc) }
            return PageText(sb.toString(), boxes.toFloatArray())
        }

        private fun readCache(f: File): PdfTextIndex? = runCatching {
            if (!f.exists()) return null
            DataInputStream(f.inputStream().buffered()).use { inp ->
                if (inp.readInt() != CACHE_VERSION) return null
                val n = inp.readInt()
                PdfTextIndex(List(n) {
                    val text = inp.readUTF8Long()
                    val boxes = FloatArray(inp.readInt()) { inp.readFloat() }
                    PageText(text, boxes)
                })
            }
        }.getOrNull()

        private fun writeCache(f: File, index: PdfTextIndex) {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(CACHE_VERSION)
                out.writeInt(index.pages.size)
                for (p in index.pages) {
                    out.writeUTF8Long(p.text)
                    out.writeInt(p.boxes.size)
                    for (v in p.boxes) out.writeFloat(v)
                }
            }
            tmp.renameTo(f)
        }

        // writeUTF is limited to 64 KB; a dense page can exceed that.
        private fun DataOutputStream.writeUTF8Long(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            writeInt(bytes.size)
            write(bytes)
        }

        private fun DataInputStream.readUTF8Long(): String {
            val bytes = ByteArray(readInt())
            readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }
}
