package com.narl3yyy.pdfcs.bench

import com.narl3yyy.pdfcs.camera.PageImage
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.hypot

/**
 * Page-finding benchmark over the scenes made by tools/benchmark/make_set.sh. Runs only when
 * `-Dpdfcs.scenes=DIR` is given (the scenes are large, so they are not part of the repository):
 *
 *     ./gradlew testDebugUnitTest --tests '*SceneBenchmark*' -Dpdfcs.scenes=/path/to/set
 *
 * For each photo it reports the worst corner error as a share of the page diagonal.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SceneBenchmark {
    @Test fun findPages() {
        val dir = System.getProperty("pdfcs.scenes")?.let(::File)
        assumeTrue("set -Dpdfcs.scenes to run the benchmark", dir?.isDirectory == true)
        val rows = ArrayList<String>()
        var ok = 0
        var n = 0
        for (photo in dir!!.listFiles { f -> f.name.startsWith("scene_") && f.name.endsWith(".jpg") }!!.sorted()) {
            val truth = JSONObject(File(photo.path.removeSuffix(".jpg") + ".json").readText())
            val w = truth.getDouble("width"); val h = truth.getDouble("height")
            val c = truth.getJSONArray("corners")
            val gt = List(4) { c.getJSONArray(it).let { p -> p.getDouble(0) / w to p.getDouble(1) / h } }
            val diag = hypot((gt[2].first - gt[0].first) * w, (gt[2].second - gt[0].second) * h)
            val trace = ArrayList<String>()
            com.narl3yyy.pdfcs.camera.PageFinder.trace = { trace += it }
            val quad = PageImage.detectPage(photo)
            com.narl3yyy.pdfcs.camera.PageFinder.trace = null
            // Corners the photo cut off cannot be found; judge the ones inside the frame.
            val visible = (0 until 4).filter { i -> gt[i].first in 0.0..1.0 && gt[i].second in 0.0..1.0 }
            val err = if (quad == null) Double.NaN else visible.maxOfOrNull { i ->
                hypot((quad[i * 2] - gt[i].first) * w, (quad[i * 2 + 1] - gt[i].second) * h) / diag * 100
            } ?: 0.0
            n++
            if (!err.isNaN() && err < 1.5) ok++
            rows += "%-28s %s%s".format(photo.name, if (err.isNaN()) "NOT FOUND" else "%.2f%%".format(err), if (visible.size < 4) "  (${4 - visible.size} corner(s) outside the photo)" else "")
            File(photo.path.removeSuffix(".jpg") + ".found").writeText(quad?.joinToString(" ") ?: "")
            if (err.isNaN() || err >= 1.5) {
                trace.forEach { rows += "    $it" }
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                val bmp = android.graphics.BitmapFactory.decodeFile(photo.path, opts)
                val s = 1200f / maxOf(bmp.width, bmp.height)
                val small = android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
                val px = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
                for ((q, sc) in com.narl3yyy.pdfcs.camera.PageFinder.analyse(px, small.width, small.height)) {
                    val e = (0 until 4).maxOf { i -> hypot((q[i].x / small.width - gt[i].first) * w, (q[i].y / small.height - gt[i].second) * h) / diag * 100 }
                    rows += "    cand err=%6.2f%% q=%s %s".format(e, q.joinToString(",") { "%.0f:%.0f".format(it.x / small.width * 1000, it.y / small.height * 1000) }, sc?.let { "score=%.3f pIn=%.2f con=%.2f bgIn=%.2f".format(it.score, it.pIn, it.contrast, it.pOut) } ?: "rejected")
                }
            }
        }
        val report = rows.joinToString("\n") + "\nfound within 1.5%%: %d/%d".format(ok, n)
        File(dir, "report.txt").writeText(report)
        println(report)
    }

    /**
     * Pages that are already flat (a scanned PDF, a screenshot): page finding must leave them whole,
     * never crop to a figure or a text block. Files named flat_*.jpg.
     */
    @Test fun flatPages() {
        val dir = System.getProperty("pdfcs.scenes")?.let(::File)
        assumeTrue("set -Dpdfcs.scenes to run the benchmark", dir?.isDirectory == true)
        val rows = ArrayList<String>()
        var ok = 0
        var n = 0
        for (f in dir!!.listFiles { f -> f.name.startsWith("flat_") && f.name.endsWith(".jpg") }!!.sorted()) {
            val quad = PageImage.detectPageInPdf(f)
            val full = listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
            val err = quad?.let { q -> (0 until 8).maxOf { kotlin.math.abs(q[it] - full[it]) } * 100 } ?: 0f
            n++
            if (err < 2f) ok++
            rows += "%-28s %s".format(f.name, if (quad == null) "left whole" else "quad off by %.1f%% %s".format(err, quad.joinToString(" ") { "%.3f".format(it) }))
        }
        val report = rows.joinToString("\n") + "\nflat pages left whole: %d/%d".format(ok, n)
        File(dir, "flat.txt").writeText(report)
        println(report)
    }

    /** The full page pipeline (find, straighten, trim, clean): remaining tilt and background. */
    @Test fun cleanPages() {
        val dir = System.getProperty("pdfcs.scenes")?.let(::File)
        assumeTrue("set -Dpdfcs.scenes to run the benchmark", dir?.isDirectory == true)
        val rows = ArrayList<String>()
        var tiltOk = 0
        var upright = 0
        var cleanOk = 0
        var n = 0
        var books = 0
        // Only book pages have plain paper at their edges; datasheet pages print bands there.
        val sources = File(dir, "sources.txt").takeIf { it.exists() }?.readLines()?.associate { l -> l.substringBefore(' ') to l.substringAfter(' ') }.orEmpty()
        for (photo in dir!!.listFiles { f -> f.name.startsWith("scene_") && f.name.endsWith(".jpg") }!!.sorted()) {
            val isBook = sources[photo.name]?.startsWith("book") != false
            val quad = PageImage.detectPage(photo)
            val page = com.narl3yyy.pdfcs.library.ScanPage(source = photo.name, quad = quad, look = com.narl3yyy.pdfcs.camera.Look.COLOUR)
            val bmp = PageImage.render(photo, page, 1400)
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
            val tilt = com.narl3yyy.pdfcs.camera.Deskew.angle(px, w, h)
            val lean = com.narl3yyy.pdfcs.camera.Deskew.shear(px, w, h)
            if (kotlin.math.abs(lean) < 0.006) upright++
            // Background left: border-band pixels (2% wide) that are neither paper-white nor grey ink.
            var band = 0
            var bad = 0
            for (y in 0 until h) for (x in 0 until w) {
                if (x > w * 0.02 && x < w * 0.98 && y > h * 0.02 && y < h * 0.98) continue
                band++
                val p = px[y * w + x]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                val chroma = maxOf(r, g, b) - minOf(r, g, b)
                if (chroma > 40 || (minOf(r, g, b) < 200 && chroma > 20)) bad++
            }
            val residue = bad * 100.0 / band
            n++
            if (kotlin.math.abs(tilt) < 0.5) tiltOk++
            if (isBook) { books++; if (residue < 5.0) cleanOk++ }
            rows += "%-28s tilt=%5.2f°  lean=%6.3f  background=%s  %dx%d".format(photo.name, tilt, lean, if (isBook) "%5.1f%%".format(residue) else "  n/a", w, h)
            File(dir, "clean_" + photo.name).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
            bmp.recycle()
        }
        val report = rows.joinToString("\n") + "\nlevel (|tilt| < 0.5°): %d/%d   upright (|lean| < 0.6%%): %d/%d   clean book-page edges (< 5%% background): %d/%d".format(tiltOk, n, upright, n, cleanOk, books)
        File(dir, "clean.txt").writeText(report)
        println(report)
    }
}
