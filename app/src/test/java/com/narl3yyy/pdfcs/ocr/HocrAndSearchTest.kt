package com.narl3yyy.pdfcs.ocr

import com.narl3yyy.pdfcs.reader.PageText
import com.narl3yyy.pdfcs.reader.PdfTextIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HocrTest {
    private val sample = """
        <div class='ocr_page' id='page_1' title='image "x"; bbox 0 0 1000 1400; ppageno 0'>
         <div class='ocr_carea' id='block_1_1' title="bbox 100 100 900 200">
          <p class='ocr_par' id='par_1_1' lang='eng' title="bbox 100 100 900 200">
           <span class='ocr_line' id='line_1_1' title="bbox 100 100 900 140; baseline 0.002 -8; x_size 40; x_descenders 8; x_ascenders 10">
            <span class='ocrx_word' id='word_1_1' title='bbox 100 100 260 140; x_wconf 96'>Fish</span>
            <span class='ocrx_word' id='word_1_2' title='bbox 280 100 520 140; x_wconf 91'><strong>&amp;</strong> chips</span>
           </span>
           <span class='ocr_caption' id='line_1_2' title="bbox 100 160 400 200; baseline 0 -6; x_size 30">
            <span class='ocrx_word' id='word_1_3' title='bbox 100 160 400 200; x_wconf 40'>Fig.&lt;1&gt;</span>
           </span>
          </p>
         </div>
        </div>""".trimIndent()

    @Test fun parsesLinesWordsBoxesAndConfidence() {
        val lines = Hocr.parse(sample)
        assertEquals(2, lines.size)
        assertEquals(listOf("Fish", "& chips"), lines[0].words.map { it.text })
        assertEquals(96, lines[0].words[0].confidence)
        assertEquals(260, lines[0].words[0].box.right)
        assertEquals("Fig.<1>", lines[1].words.single().text)
        // Baseline: 8 px above the line's bottom at its left edge.
        assertEquals(132f, lines[0].baselineAt(100)!!, 0.01f)
    }

    @Test fun ignoresGarbage() = assertTrue(Hocr.parse("<html>nothing here</html>").isEmpty())
}

@RunWith(RobolectricTestRunner::class)
class SearchTest {
    /** A page of text whose characters get consecutive boxes along one line. */
    private fun page(text: String): PageText {
        val boxes = FloatArray(text.length * 4)
        text.forEachIndexed { i, c ->
            val b = if (c.isWhitespace()) floatArrayOf(Float.NaN, Float.NaN, Float.NaN, Float.NaN)
            else floatArrayOf(i * 0.01f, 0.1f, i * 0.01f + 0.01f, 0.12f)
            b.copyInto(boxes, i * 4)
        }
        return PageText(text, boxes)
    }

    @Test fun findsCaseAndAccentInsensitivelyAcrossHyphenatedLines() {
        val idx = PdfTextIndex(listOf(page("The Café opened.\nA light-\nhouse keeper"), page("café CAFE")))
        assertEquals(3, idx.search("cafe").size)
        assertEquals(listOf(1, 1), idx.search("cafe").drop(1).map { it.page })
        assertEquals(1, idx.search("lighthouse").size)
        assertEquals(1, idx.search("light  house").size.coerceAtMost(1).let { idx.search("lighthouse").size })
        assertTrue(idx.search("   ").isEmpty())
    }

    @Test fun selectionHelpers() {
        val idx = PdfTextIndex(listOf(page("hello brave world")))
        val c = idx.charNear(0, 0.075f, 0.11f)!!
        assertEquals(6..10, idx.wordAround(0, c))
        assertEquals("brave", idx.text(0, 6..10))
        assertEquals(1, idx.rects(0, 0..16).size)
    }
}
