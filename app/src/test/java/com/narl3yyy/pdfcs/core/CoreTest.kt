package com.narl3yyy.pdfcs.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistogramTest {
    @Test fun otsuSplitsInkFromPaper() {
        val hist = IntArray(256)
        hist[30] = 1000 // ink
        hist[230] = 9000 // paper
        val t = Histogram.otsu(hist)
        assertTrue("threshold $t lies between ink and paper", t in 30 until 230)
    }

    @Test fun otsuOfEmptyHistogramIsMidGrey() = assertEquals(128, Histogram.otsu(IntArray(256)))

    @Test fun percentile() {
        val hist = IntArray(256).also { it[10] = 50; it[200] = 50 }
        assertEquals(10, Histogram.percentile(hist, 0.25))
        assertEquals(200, Histogram.percentile(hist, 0.75))
    }

    @Test fun weightedMedian() {
        assertEquals(5f, Stats.weightedMedian(listOf(1f to 1, 5f to 10, 9f to 1)))
        assertNull(Stats.weightedMedian(emptyList()))
    }
}

class QuadTest {
    @Test fun ordersCornersClockwiseFromTopLeft() {
        val q = Quad.order(listOf(Pt(100.0, 90.0), Pt(0.0, 0.0), Pt(5.0, 100.0), Pt(95.0, 3.0)))
        assertNotNull(q)
        assertEquals(listOf(Pt(0.0, 0.0), Pt(95.0, 3.0), Pt(100.0, 90.0), Pt(5.0, 100.0)), q)
    }

    @Test fun rejectsDegenerateAndNonConvexShapes() {
        assertNull(Quad.order(listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(20.0, 0.0), Pt(30.0, 0.0))))
        assertNull(Quad.order(listOf(Pt(0.0, 0.0), Pt(1.0, Double.NaN), Pt(2.0, 2.0), Pt(0.0, 2.0))))
    }

    @Test fun area() = assertEquals(200.0, Quad.area(listOf(Pt(0.0, 0.0), Pt(20.0, 0.0), Pt(20.0, 10.0), Pt(0.0, 10.0))), 1e-9)
}

class PageGeometryTest {
    private fun roundTrip(rotation: Int) {
        val g = PageGeometry(10f, 20f, 600f, 800f, rotation)
        for ((u, v) in listOf(0f to 0f, 1f to 0f, 0.25f to 0.75f, 1f to 1f)) {
            val (x, y) = g.toUser(u, v)
            val (u2, v2) = g.toDisplay(x, y)
            assertEquals("u at $rotation°", u, u2, 1e-4f)
            assertEquals("v at $rotation°", v, v2, 1e-4f)
        }
    }

    @Test fun roundTripsAtEveryRotation() = listOf(0, 90, 180, 270, -90, 450).forEach(::roundTrip)

    @Test fun unrotatedTopLeftIsUserTopLeft() {
        val (x, y) = PageGeometry(0f, 0f, 600f, 800f, 0).toUser(0f, 0f)
        assertEquals(0f, x); assertEquals(800f, y)
    }

    @Test fun quarterTurnShowsBottomLeftAtTopLeft() {
        // /Rotate 90 turns the page clockwise: its bottom-left corner appears at the top left.
        val g = PageGeometry(0f, 0f, 600f, 800f, 90)
        assertEquals(0f to 0f, g.toUser(0f, 0f))
        assertEquals(800f, g.displayWidth)
        assertEquals(600f, g.displayHeight)
    }
}

class PageRangesTest {
    @Test fun parsesListsAndRanges() {
        assertEquals(listOf(0, 1, 2, 4), PageRanges.parse("1-3, 5", 10))
        assertEquals(listOf(7, 8, 9), PageRanges.parse("8-", 10))
        assertEquals(listOf(0, 1), PageRanges.parse("-2", 10))
        assertEquals(listOf(4, 3, 2), PageRanges.parse("5-3", 10))
        assertEquals(listOf(1), PageRanges.parse("2, 2", 10))
    }

    @Test fun rejectsInvalidInput() {
        assertNull(PageRanges.parse("", 10))
        assertNull(PageRanges.parse("0", 10))
        assertNull(PageRanges.parse("11", 10))
        assertNull(PageRanges.parse("a-b", 10))
        assertNull(PageRanges.parse("1", 0))
    }

    @Test fun formatsCompactly() {
        assertEquals("1–3, 5", PageRanges.format(listOf(2, 0, 1, 4)))
        assertEquals("", PageRanges.format(emptyList()))
    }
}
