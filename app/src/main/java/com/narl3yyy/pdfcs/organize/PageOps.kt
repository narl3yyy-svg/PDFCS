package com.narl3yyy.pdfcs.organize

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.io.File

/**
 * Page and document-structure edits (after pdfcraft-organize). Page numbers are 0-based here;
 * messages name the 1-based page a reader counts. Operations act on a loaded document inside a
 * [com.narl3yyy.pdfcs.engine.DocumentSession] step, so every one of them is undoable.
 */
object PageOps {

    private fun check(doc: PDDocument, pages: Collection<Int>) {
        val n = doc.numberOfPages
        pages.forEach { require(it in 0 until n) { "There is no page ${it + 1} (the document has $n)" } }
    }

    /** Rotates pages by a multiple of 90° (positive = clockwise), adjusting /Rotate. */
    fun rotate(doc: PDDocument, pages: Collection<Int>, degrees: Int) {
        require(degrees % 90 == 0) { "Pages turn in quarter turns" }
        check(doc, pages)
        for (i in pages) {
            val p = doc.getPage(i)
            p.rotation = Math.floorMod(p.rotation + degrees, 360)
        }
    }

    /** Deletes pages. Refuses to delete every page; bookmarks to deleted pages are removed. */
    fun delete(doc: PDDocument, pages: Collection<Int>) {
        check(doc, pages)
        require(pages.toSet().size < doc.numberOfPages) { "A document needs at least one page" }
        val gone = pages.toSet().map { doc.getPage(it) }.toSet()
        doc.documentCatalog.documentOutline?.let { pruneOutline(it, gone) }
        // Highest first, so earlier indices stay valid. (Not SortedSet.reversed(): that is Java 21 only.)
        for (i in pages.toSet().sortedDescending()) doc.removePage(i)
    }

    /** Moves the [pages] (in their current order) so that they start at [to]. */
    fun move(doc: PDDocument, pages: List<Int>, to: Int) {
        check(doc, pages)
        val order = (0 until doc.numberOfPages).toMutableList()
        val moving = pages.distinct().sorted()
        order.removeAll(moving.toSet())
        val at = (to - moving.count { it < to }).coerceIn(0, order.size)
        order.addAll(at, moving)
        reorder(doc, order)
    }

    /** Puts the pages in [order] (a permutation of 0 until pageCount). */
    fun reorder(doc: PDDocument, order: List<Int>) {
        val n = doc.numberOfPages
        require(order.size == n && order.toSet() == (0 until n).toSet()) { "The new order must list every page once" }
        val pages = (0 until n).map { doc.getPage(it) }
        for (i in n - 1 downTo 0) doc.removePage(i)
        order.forEach { doc.addPage(pages[it]) }
    }

    fun reverse(doc: PDDocument) = reorder(doc, (doc.numberOfPages - 1 downTo 0).toList())

    /** Inserts a blank page the size of its neighbour at [at]. */
    fun insertBlank(doc: PDDocument, at: Int) {
        val n = doc.numberOfPages
        val ref = if (n == 0) null else doc.getPage((at - 1).coerceIn(0, n - 1))
        val page = PDPage(ref?.mediaBox?.let { PDRectangle(it.width, it.height) } ?: PDRectangle.A4)
        insertAt(doc, page, at)
    }

    /** Duplicates a page right after itself. */
    fun duplicate(doc: PDDocument, page: Int) {
        check(doc, listOf(page))
        val src = doc.getPage(page)
        val copy = doc.importPage(src) // appended; move it into place
        copy.rotation = src.rotation
        val order = (0 until doc.numberOfPages).toMutableList()
        order.removeAt(order.lastIndex)
        order.add(page + 1, doc.numberOfPages - 1)
        reorder(doc, order)
    }

    /** Inserts every page of [other] at [at]. */
    fun insertFile(doc: PDDocument, other: File, at: Int, password: String? = null) {
        PDDocument.load(other, password ?: "", MemoryUsageSetting.setupTempFileOnly()).use { src ->
            val start = doc.numberOfPages
            PDFMergerUtility().appendDocument(doc, src)
            val added = (start until doc.numberOfPages).toList()
            if (at < start) move(doc, added, at)
        }
    }

    private fun insertAt(doc: PDDocument, page: PDPage, at: Int) {
        doc.addPage(page)
        val n = doc.numberOfPages
        val order = (0 until n - 1).toMutableList()
        order.add(at.coerceIn(0, n - 1), n - 1)
        reorder(doc, order)
    }

    /** Writes the [pages] into a new PDF at [out]. */
    fun extract(doc: PDDocument, pages: List<Int>, out: File) {
        check(doc, pages)
        PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { dst ->
            for (i in pages) {
                val imported = dst.importPage(doc.getPage(i))
                imported.rotation = doc.getPage(i).rotation
            }
            dst.save(out)
        }
    }

    /** Splits into documents of [every] pages each; returns the parts in order. */
    fun split(doc: PDDocument, every: Int, outDir: File, baseName: String): List<File> {
        require(every >= 1) { "Each part needs at least one page" }
        outDir.mkdirs()
        val parts = Splitter().apply { setSplitAtPage(every) }.split(doc)
        return parts.mapIndexed { i, part ->
            part.use { p ->
                File(outDir, "$baseName (part ${i + 1}).pdf").also { p.save(it) }
            }
        }
    }

    /** Joins [files] in order into [out] (Combine Files). Links, fields and bookmarks come along. */
    fun combine(files: List<File>, out: File) {
        require(files.size >= 2) { "Pick at least two documents to combine" }
        PDFMergerUtility().apply {
            files.forEach { addSource(it) }
            destinationFileName = out.path
            mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
        }
    }

    /**
     * Unlinks an outline item from its parent's chain (ISO 32000-2 §12.3.3: /Prev, /Next, /First,
     * /Last); its open descendants no longer count towards the parent's /Count.
     */
    private fun unlink(parent: PDOutlineNode, item: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem) {
        val d = item.cosObject
        val p = parent.cosObject
        val prev = d.getDictionaryObject(COSName.PREV) as? COSDictionary
        val next = d.getDictionaryObject(COSName.NEXT) as? COSDictionary
        if (prev != null) { if (next != null) prev.setItem(COSName.NEXT, next) else prev.removeItem(COSName.NEXT) }
        else if (next != null) p.setItem(COSName.FIRST, next) else p.removeItem(COSName.FIRST)
        if (next != null) { if (prev != null) next.setItem(COSName.PREV, prev) else next.removeItem(COSName.PREV) }
        else if (prev != null) p.setItem(COSName.LAST, prev) else p.removeItem(COSName.LAST)
        val count = p.getInt(COSName.COUNT, 0)
        if (count != 0) {
            val removed = 1 + maxOf(0, d.getInt(COSName.COUNT, 0))
            val left = if (count > 0) maxOf(0, count - removed) else minOf(0, count + removed)
            if (left == 0) p.removeItem(COSName.COUNT) else p.setInt(COSName.COUNT, left)
        }
    }

    /** Removes outline items (and their children) whose destination is one of the [gone] pages. */
    private fun pruneOutline(node: PDOutlineNode, gone: Set<PDPage>) {
        var child = node.firstChild
        while (child != null) {
            val next = child.nextSibling
            val dest = runCatching { child.destination as? PDPageDestination }.getOrNull()
                ?: runCatching { (child.action as? com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo)?.destination as? PDPageDestination }.getOrNull()
            if (dest?.page != null && dest.page in gone) {
                unlink(node, child)
            } else {
                pruneOutline(child, gone)
            }
            child = next
        }
    }
}
