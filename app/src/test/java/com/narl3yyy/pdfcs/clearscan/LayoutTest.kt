package com.narl3yyy.pdfcs.clearscan

import com.narl3yyy.pdfcs.ocr.CaseFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PageLayoutTest {
    /** A Letter page photographed at [k] pixels per point, text block 1"×1" in from the sheet's edges, 10 pt body. */
    private fun letterPhoto(k: Float, body: Boolean = true) = PageLayout.Measure(
        (612 * k).toInt(), (792 * k).toInt(),
        intArrayOf((72 * k).toInt(), (72 * k).toInt(), (540 * k).toInt(), (720 * k).toInt()),
        if (body) 10f * k else null,
    )

    private fun near(a: Float, b: Float, tol: Float = 1.5f) = abs(a - b) <= tol

    @Test fun trueCropsKeepTheSheetsOwnLayout() {
        val placed = PageLayout.layout(listOf(letterPhoto(4f), letterPhoto(3.2f), letterPhoto(5f)))
        for (p in placed) {
            assertEquals(612f, p.paperW); assertEquals(792f, p.paperH)
            // The photo covers the paper exactly, so its text block lands an inch in.
            assertTrue(near(p.originX, 0f) && near(p.originTop, 0f))
        }
        assertTrue(near(placed[0].scale * 4f, 1f, 0.01f))
    }

    @Test fun aTightCropIsSetAtTheDocumentsSizeAndMargins() {
        val good = List(4) { letterPhoto(4f) }
        // Cropped right at the text: 468×648 pt of text, photographed at 4 px/pt.
        val tight = PageLayout.Measure(468 * 4, 648 * 4, intArrayOf(0, 0, 468 * 4, 648 * 4), 40f)
        val p = PageLayout.layout(good + tight).last()
        assertEquals(612f, p.paperW)
        assertTrue("body text at the document's 10 pt", near(p.scale * 40f, 10f, 0.05f))
        assertTrue("text block starts an inch in, at ${p.originX}, ${p.originTop}", near(p.originX, 72f) && near(p.originTop, 72f))
    }

    @Test fun aCropWithTheDeskIsNotTakenAsThePage() {
        val good = List(4) { letterPhoto(4f) }
        // The sheet plus a band of desk on the right: a wider photo, the text in its left part.
        val desk = PageLayout.Measure(800 * 4, 792 * 4, intArrayOf(72 * 4, 72 * 4, 540 * 4, 720 * 4), 40f)
        val p = PageLayout.layout(good + desk).last()
        assertEquals("still portrait Letter", 612f, p.paperW)
        assertTrue(near(p.scale * 40f, 10f, 0.05f))
        assertTrue("content lands where the other pages' does", near(p.originX + 72 * 4 * p.scale, 72f))
    }

    @Test fun paperIsVotedByThePhotosShape() {
        val a4 = PageLayout.Measure(2100, 2970, null, null)
        val odd = PageLayout.Measure(2000, 2100, null, null)
        assertEquals(595.28f, PageLayout.paperFor(listOf(a4, a4, odd, a4)).first)
    }

    @Test fun aFigurePageIsNeverBlownUp() {
        val good = List(3) { letterPhoto(4f) }
        // A small drawing, cut out tightly (no body text): fitted to the text block at most.
        val fig = PageLayout.Measure(400, 300, intArrayOf(0, 0, 400, 300), null)
        val p = PageLayout.layout(good + fig).last()
        assertTrue(400 * p.scale <= 612f - 2 * PageLayout.MIN_MARGIN + 0.5f)
        assertTrue(p.originTop + 300 * p.scale <= 792f)
    }

    @Test fun everyPageFitsItsPaper() {
        val pages = listOf(
            letterPhoto(4f),
            PageLayout.Measure(3000, 1000, intArrayOf(0, 0, 3000, 1000), 20f), // a wide table, tight
            PageLayout.Measure(500, 4000, intArrayOf(0, 0, 500, 4000), 10f),
        )
        for ((m, p) in pages.zip(PageLayout.layout(pages))) {
            val c = m.content!!
            assertTrue(p.originX + c[0] * p.scale >= -0.5f && p.originX + c[2] * p.scale <= p.paperW + 0.5f)
            assertTrue(p.originTop + c[1] * p.scale >= -0.5f && p.originTop + c[3] * p.scale <= p.paperH + 0.5f)
        }
    }
}

class CaseFixTest {
    @Test fun printCasesArePlausible() {
        for (w in listOf("requirements", "Requirements", "REQUIREMENTS", "PTHs", "BGAs", "SnPb", "mA", "kHz", "PowerPoint", "iPhone", "Pb-Free", "and/or", "x", "IPC-7093", "(D1D2D3]", "F=1MHz", "C(pF)", "MHz", "VRM=5.25V", "Ipp=1A", "kΩ", "(MHz)"))
            assertTrue(w, CaseFix.plausible(w))
    }

    @Test fun misreadCaseIsPutRight() {
        assertEquals("Requirements", CaseFix.fix("ReqUIrements"))
        assertEquals("Cooling", CaseFix.fix("COOling"))
        assertEquals("COMPONENTS", CaseFix.fix("COMPONENts"))
        assertEquals("comply", CaseFix.fix("comPLy"))
        assertFalse("not a formula", CaseFix.plausible("ThIs"))
        assertTrue(CaseFix.plausible("SnAgCu"))
        assertEquals("(Cooling,", CaseFix.fix("(COOling,"))
        assertFalse(CaseFix.plausible("THis"))
        assertTrue(CaseFix.candidates("THis").containsAll(listOf("This", "THIS")))
    }
}
