package com.narl3yyy.pdfcs.engine

import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.narl3yyy.pdfcs.annot.Annotations
import com.narl3yyy.pdfcs.annot.Markup
import com.narl3yyy.pdfcs.crypt.Permissions
import com.narl3yyy.pdfcs.crypt.Protection
import com.narl3yyy.pdfcs.inspect.DocumentInfo
import com.narl3yyy.pdfcs.inspect.Metadata
import com.narl3yyy.pdfcs.organize.PageOps
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Pages are told apart by their width: page n is (100 + n) points wide. */
private fun widths(doc: PDDocument) = (0 until doc.numberOfPages).map { doc.getPage(it).mediaBox.width.toInt() - 100 }

private fun numbered(n: Int) = PDDocument().apply { repeat(n) { addPage(PDPage(PDRectangle(100f + it, 200f))) } }

@RunWith(RobolectricTestRunner::class)
class PageOpsTest {
    @Test fun moveReorderReverse() { numbered(5).use { doc ->
        PageOps.move(doc, listOf(3, 4), 0)
        assertEquals(listOf(3, 4, 0, 1, 2), widths(doc))
        PageOps.move(doc, listOf(0), 5) // first page to the end
        assertEquals(listOf(4, 0, 1, 2, 3), widths(doc))
        PageOps.reverse(doc)
        assertEquals(listOf(3, 2, 1, 0, 4), widths(doc))
    } }

    @Test fun movingOnePageLaterByOne() { numbered(4).use { doc ->
        PageOps.move(doc, listOf(1), 1 + 1 + 1)
        assertEquals(listOf(0, 2, 1, 3), widths(doc))
    } }

    @Test fun deleteRefusesToEmptyTheDocument() { numbered(3).use { doc ->
        PageOps.delete(doc, listOf(0, 2))
        assertEquals(listOf(1), widths(doc))
        assertThrows(IllegalArgumentException::class.java) { PageOps.delete(doc, listOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { PageOps.delete(doc, listOf(5)) }
    } }

    @Test fun rotateDuplicateInsertBlank() { numbered(3).use { doc ->
        PageOps.rotate(doc, listOf(1), 90)
        PageOps.rotate(doc, listOf(1), 90)
        assertEquals(180, doc.getPage(1).rotation)
        PageOps.duplicate(doc, 1)
        assertEquals(listOf(0, 1, 1, 2), widths(doc))
        assertEquals(180, doc.getPage(2).rotation)
        PageOps.insertBlank(doc, 0)
        assertEquals(5, doc.numberOfPages)
        assertEquals(listOf(0, 0, 1, 1, 2), widths(doc)) // the blank copies its neighbour's size
    } }

    @Test fun extractSplitCombine() {
        val dir = createTempDir()
        try {
            numbered(5).use { doc ->
                val out = File(dir, "x.pdf")
                PageOps.extract(doc, listOf(4, 1), out)
                PDDocument.load(out).use { assertEquals(listOf(4, 1), widths(it)) }
                val parts = PageOps.split(doc, 2, dir, "book")
                assertEquals(3, parts.size)
                val joined = File(dir, "joined.pdf")
                PageOps.combine(parts, joined)
                PDDocument.load(joined).use { assertEquals(listOf(0, 1, 2, 3, 4), widths(it)) }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class SessionAndSecurityTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() = PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())

    private fun file(n: Int): File = tmp.newFile("doc.pdf").also { f -> numbered(n).use { it.save(f) } }

    @Test fun editsAreUndoableAndRedoable() = runBlocking {
        val s = DocumentSession(file(3), tmp.newFolder())
        s.edit("Delete") { PageOps.delete(it, listOf(0)) }
        assertEquals("Delete", s.history.value.undoLabel)
        s.read { assertEquals(listOf(1, 2), widths(it)) }
        assertTrue(s.undo())
        s.read { assertEquals(listOf(0, 1, 2), widths(it)) }
        assertTrue(s.redo())
        s.read { assertEquals(listOf(1, 2), widths(it)) }
        assertFalse(s.redo())
    }

    @Test fun aFailedEditLeavesTheFileAsItWas() = runBlocking {
        val f = file(2)
        val before = f.readBytes()
        val s = DocumentSession(f, tmp.newFolder())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { s.edit("Delete all") { PageOps.delete(it, listOf(0, 1)) } } }
        assertTrue(before.contentEquals(f.readBytes()))
        assertNull(s.history.value.undoLabel)
    }

    @Test fun protectThenOpenWithPasswordThenUnprotect() = runBlocking {
        val f = file(2)
        val s = DocumentSession(f, tmp.newFolder())
        s.edit("Protect") { Protection.protect(it, "open-me", "owner-pw", Permissions(copy = false)) }
        assertThrows(InvalidPasswordException::class.java) { PDDocument.load(f).close() }
        assertTrue(s.needsPassword())
        PDDocument.load(f, "open-me").use { doc ->
            assertTrue(doc.isEncrypted)
            assertEquals(256, doc.encryption.length)
            assertFalse(doc.currentAccessPermission.canExtractContent())
        }
        s.password = "open-me"
        s.edit("Remove password") { Protection.unprotect(it) }
        PDDocument.load(f).use { assertFalse(it.isEncrypted) }
    }

    @Test fun metadataRoundTrips() = runBlocking {
        val s = DocumentSession(file(1), tmp.newFolder())
        s.edit("Properties") { DocumentInfo.setMetadata(it, Metadata("A Title", "Ann Author", "", "pdf, scan")) }
        s.read { assertEquals(Metadata("A Title", "Ann Author", "", "pdf, scan"), DocumentInfo.metadata(it)) }
    }
}

@RunWith(RobolectricTestRunner::class)
class AnnotationsTest {
    @Before fun setUp() = PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())

    @Test fun highlightMapsDisplayRectsToUserSpace() { PDDocument().use { doc ->
        doc.addPage(PDPage(PDRectangle(600f, 800f)))
        // Top-left quarter of the displayed page.
        Annotations.addMarkup(doc, 0, Markup.HIGHLIGHT, listOf(RectF(0f, 0f, 0.5f, 0.25f)), "quoted")
        val a = doc.getPage(0).annotations.single()
        // QuadPoints: upper-left, upper-right, lower-left, lower-right (the rectangle is padded for the appearance).
        val q = (a as com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup).quadPoints.toList()
        assertEquals(listOf(0f, 800f, 300f, 800f, 0f, 600f, 300f, 600f), q)
        assertNotNull("has an appearance", a.appearance?.normalAppearance)
        assertEquals("quoted", a.contents)
    } }

    @Test fun highlightOnARotatedPage() { PDDocument().use { doc ->
        doc.addPage(PDPage(PDRectangle(600f, 800f)).apply { rotation = 90 })
        // The displayed top-left corner of a /Rotate 90 page is its user-space bottom-left.
        Annotations.addMarkup(doc, 0, Markup.UNDERLINE, listOf(RectF(0f, 0f, 0.1f, 0.1f)), null)
        val q = (doc.getPage(0).annotations.single() as com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup).quadPoints.toList()
        // Display (0,0) → user (0,0); display (0.1, 0) → user (0, 80); display (0, 0.1) → user (60, 0).
        assertEquals(listOf(0f, 0f, 0f, 80f, 60f, 0f, 60f, 80f), q)
    } }

    @Test fun notesAndInkAreListedAndDeletable() { PDDocument().use { doc ->
        doc.addPage(PDPage(PDRectangle.A4))
        Annotations.addNote(doc, 0, 0.5f, 0.5f, "Check this")
        Annotations.addInk(doc, 0, listOf(listOf(0.1f to 0.1f, 0.2f to 0.2f, 0.3f to 0.1f)), 0xFFE53935.toInt(), 2f)
        val list = Annotations.list(doc)
        assertEquals(listOf("Note", "Drawing"), list.map { it.kind })
        assertEquals("Check this", list[0].text)
        Annotations.delete(doc, 0, 0)
        assertEquals(listOf("Drawing"), Annotations.list(doc).map { it.kind })
    } }
}

@RunWith(RobolectricTestRunner::class)
class DisplayCopyTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() = PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())

    @Test fun drawsCommentsIntoTheCopyAndLeavesTheDocumentAlone() = runBlocking {
        val f = tmp.newFile("c.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            Annotations.addMarkup(doc, 0, Markup.HIGHLIGHT, listOf(RectF(0.1f, 0.1f, 0.5f, 0.12f)), "x")
            doc.save(f)
        }
        val copy = com.narl3yyy.pdfcs.reader.DisplayCopy.prepare(f, tmp.newFolder(), null)
        assertTrue("a separate copy", copy != f)
        PDDocument.load(copy).use { assertTrue(it.getPage(0).annotations.isEmpty()) }
        PDDocument.load(f).use { assertEquals(1, it.getPage(0).annotations.size) }
    }

    @Test fun aDocumentWithoutCommentsIsShownAsItIs() = runBlocking {
        val f = tmp.newFile("plain.pdf")
        numbered(2).use { it.save(f) }
        assertEquals(f, com.narl3yyy.pdfcs.reader.DisplayCopy.prepare(f, tmp.newFolder(), null))
    }
}
