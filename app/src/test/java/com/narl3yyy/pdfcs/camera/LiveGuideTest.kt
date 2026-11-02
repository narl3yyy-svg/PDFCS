package com.narl3yyy.pdfcs.camera

import com.narl3yyy.pdfcs.core.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveGuideTest {
    private fun page(l: Double, t: Double, r: Double, b: Double) = listOf(Pt(l, t), Pt(r, t), Pt(r, b), Pt(l, b))

    @Test fun aCentredSquarePageIsGood() = assertNull(LiveGuide.problem(page(0.1, 0.08, 0.9, 0.92)))

    @Test fun aPageRunningOffTheFrameIsCutOff() {
        val p = LiveGuide.problem(page(0.0, 0.1, 0.8, 0.9))!!
        assertTrue(p.second)
        assertTrue(p.first.contains("whole page"))
    }

    @Test fun aSmallPageNeedsCloser() = assertTrue(LiveGuide.problem(page(0.3, 0.3, 0.7, 0.7))!!.first.contains("closer"))

    @Test fun anOffCentrePageSaysWhichWay() =
        assertTrue(LiveGuide.problem(page(0.25, 0.1, 0.98, 0.9))!!.first.contains("right"))

    @Test fun aKeystonedPageIsTilted() {
        // Top edge much shorter than the bottom: the top is farther from the camera.
        val q = listOf(Pt(0.25, 0.1), Pt(0.75, 0.1), Pt(0.92, 0.9), Pt(0.08, 0.9))
        assertTrue(LiveGuide.problem(q)!!.first.contains("top edge"))
    }

    @Test fun aTurnedPageSaysTurn() {
        val a = Math.toRadians(10.0)
        val c = listOf(-0.35 to -0.42, 0.35 to -0.42, 0.35 to 0.42, -0.35 to 0.42)
            .map { (x, y) -> Pt(0.5 + x * Math.cos(a) - y * Math.sin(a), 0.5 + x * Math.sin(a) + y * Math.cos(a)) }
        assertTrue(LiveGuide.problem(c)!!.first.contains("Turn"))
    }

    @Test fun readyOnlyAfterHoldingStill() {
        var now = 0L
        val g = LiveGuide { now }
        val p = page(0.1, 0.08, 0.9, 0.92)
        assertEquals(LiveGuide.State.HOLD, g.update(p).state)
        now += 400; assertEquals(LiveGuide.State.HOLD, g.update(p).state)
        now += 400; assertEquals(LiveGuide.State.READY, g.update(p).state)
        // Moving starts the wait again.
        now += 100; assertEquals(LiveGuide.State.HOLD, g.update(page(0.13, 0.08, 0.93, 0.92)).state)
    }

    @Test fun aMissedFrameKeepsTheOutlineBriefly() {
        var now = 0L
        val g = LiveGuide { now }
        val p = page(0.1, 0.08, 0.9, 0.92)
        g.update(p)
        now += 100; assertEquals(p, g.update(null).corners)
        now += 1000; assertNull(g.update(null).corners)
    }
}
