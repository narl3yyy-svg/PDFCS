package com.narl3yyy.pdfcs.camera

import com.narl3yyy.pdfcs.core.Pt
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** Synthetic photos, as PdfCraft's camera tests do: one way to make the pixels, varied only where meant. */
private object Photos {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** A light sheet with corners [sheet] (TL, TR, BR, BL) on a dark table, with text bars and noise. */
    fun sheet(w: Int, h: Int, sheet: List<Pt>, noise: Int = 6, seed: Long = 7): IntArray {
        val textRows = (sheet.maxOf { it.y.toInt() }.let { b -> (sheet.minOf { it.y.toInt() } + 60) until b - 60 })
        val textCols = (sheet.minOf { it.x.toInt() } + 60) until (sheet.maxOf { it.x.toInt() } - 60)
        val rnd = java.util.Random(seed)
        val px = IntArray(w * h)
        fun inside(x: Double, y: Double): Boolean {
            for (i in 0 until 4) {
                val a = sheet[i]; val b = sheet[(i + 1) % 4]
                if ((b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x) < 0) return false
            }
            return true
        }
        for (y in 0 until h) for (x in 0 until w) {
            // 2×2 supersampling for smooth edges.
            var paper = 0
            for (sy in 0..1) for (sx in 0..1) if (inside(x + 0.25 + sx * 0.5, y + 0.25 + sy * 0.5)) paper++
            // Text lines well inside the sheet, as on a real page (not running into its edges).
            val text = paper == 4 && y in textRows && x in textCols && (y / 9) % 4 == 2 && (x / 7) % 5 != 0
            val v = if (text) 40 else 70 + paper * 42
            val n = rnd.nextInt(2 * noise + 1) - noise
            px[y * w + x] = argb(v + n, v + n - 4, v + n - 10)
        }
        return px
    }
}

class CameraEdgesTest {
    private fun close(a: Pt, b: Pt, tol: Double) = hypot(a.x - b.x, a.y - b.y) <= tol

    @Test fun findsATiltedSheetOnATable() {
        val corners = listOf(Pt(90.0, 70.0), Pt(520.0, 95.0), Pt(495.0, 690.0), Pt(70.0, 660.0))
        val found = CameraEdges.detect(Photos.sheet(600, 760, corners), 600, 760)
        assertNotNull("a clear sheet is found", found)
        found!!.zip(corners).forEach { (f, c) -> assertTrue("corner $f near $c", close(f, c, 8.0)) }
    }

    @Test fun findsNothingInAnEmptyTable() {
        val px = IntArray(400 * 300) { (0xFF shl 24) or 0x404040 }
        assertNull(CameraEdges.detect(px, 400, 300))
    }

    @Test fun rejectsBuffersThatDoNotMatchTheirSize() {
        assertNull(CameraEdges.detect(IntArray(10), 400, 300))
        assertNull(CameraEdges.detect(IntArray(4), 2, 2))
    }
}

class PaperRegionTest {
    @Test fun findsABrightPage() {
        val corners = listOf(Pt(60.0, 50.0), Pt(340.0, 60.0), Pt(330.0, 450.0), Pt(50.0, 440.0))
        val q = PaperRegion.detect(Photos.sheet(400, 500, corners, noise = 2), 400, 500)
        assertNotNull(q)
        assertTrue(q!![0] in 0.1f..0.2f && q[1] in 0.08f..0.14f)
    }
}

class CameraCleanTest {
    private fun lum(p: Int) = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

    /** Paper lit from the left (dark spine shadow), black text bars and a blue stamp. */
    private fun page(w: Int, h: Int): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val light = 0.45f + 0.55f * x / w
        val text = (y / 12) % 3 == 1 && x in w / 10 until 9 * w / 10 && (x / 9) % 6 != 0
        val stamp = (x - 3 * w / 4) * (x - 3 * w / 4) + (y - 3 * h / 4) * (y - 3 * h / 4) < (h / 12) * (h / 12)
        val (r, g, b) = when {
            text -> Triple(0.12f, 0.12f, 0.12f)
            stamp -> Triple(0.15f, 0.25f, 0.75f)
            else -> Triple(0.97f, 0.96f, 0.93f)
        }
        (0xFF shl 24) or ((r * light * 255).toInt() shl 16) or ((g * light * 255).toInt() shl 8) or (b * light * 255).toInt()
    }

    @Test fun evensOutLightingAndKeepsInkAndColour() {
        val w = 320; val h = 400
        val px = page(w, h)
        CameraClean.apply(px, w, h, Look.COLOUR)
        // Paper on the dark (left) side turns as white as on the lit side.
        val leftPaper = lum(px[5 * w + 8])
        val rightPaper = lum(px[5 * w + w - 8])
        assertTrue("left paper $leftPaper is white", leftPaper > 235)
        assertTrue("right paper $rightPaper is white", rightPaper > 235)
        // Text stays dark.
        val textY = 12 + 3; val textX = w / 10 + 2
        assertTrue("text stays dark", lum(px[textY * w + textX]) < 90)
        // The stamp stays blue.
        // A stamp pixel between text lines (text is drawn over the stamp).
        val s = px[(3 * h / 4 - 12) * w + 3 * w / 4]
        assertTrue("stamp stays blue: r=${(s shr 16) and 0xFF} g=${(s shr 8) and 0xFF} b=${s and 0xFF}", (s and 0xFF) > ((s shr 16) and 0xFF) + 60)
    }

    @Test fun blackAndWhiteIsTwoTone() {
        val w = 200; val h = 240
        val px = page(w, h)
        CameraClean.apply(px, w, h, Look.BLACK_WHITE)
        assertTrue(px.all { it == -1 || it == 0xFF000000.toInt() })
    }

    @Test fun originalIsUntouched() {
        val px = page(64, 64)
        val copy = px.copyOf()
        CameraClean.apply(px, 64, 64, Look.ORIGINAL)
        assertTrue(px.contentEquals(copy))
    }
}
