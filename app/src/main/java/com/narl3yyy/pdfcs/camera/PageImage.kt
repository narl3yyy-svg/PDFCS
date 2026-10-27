package com.narl3yyy.pdfcs.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import com.narl3yyy.pdfcs.library.ScanPage
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Turning a page photo into a clean, upright page image. */
object PageImage {

    /** Full pipeline for one page: decode → straighten → rotate → look. */
    fun render(source: File, page: ScanPage, maxSide: Int, look: Look = page.look): Bitmap {
        // Straightening shrinks the image, so decode a little larger than the target.
        val decodeSide = if (page.quad != null) (maxSide * 1.4f).toInt() else maxSide
        var bmp = decode(source, decodeSide)
        // The desk or cloth around the page: what the edge trim may remove.
        val background = page.quad?.let { sceneAround(bmp, it) }.orEmpty()
        page.quad?.let { q -> bmp = warp(bmp, q).also { bmp.recycle() } }
        if (page.rotation % 360 != 0) bmp = rotate(bmp, page.rotation).also { bmp.recycle() }
        bmp = straighten(bmp, background)
        bmp = scaleDown(bmp, maxSide)
        if (look != Look.ORIGINAL) applyLook(bmp, look)
        return bmp
    }

    /**
     * Cuts background strips left along the edges, turns the page so its text lines are level and
     * stands leaning text columns upright ([Deskew]). Measured on a small copy; the full page is
     * cropped, rotated and sheared once each.
     */
    fun straighten(src: Bitmap, background: List<Int> = emptyList()): Bitmap {
        var bmp = src
        val probe = { b: Bitmap ->
            val k = min(1f, 1000f / max(b.width, b.height))
            val s = Bitmap.createScaledBitmap(b, (b.width * k).roundToInt().coerceAtLeast(1), (b.height * k).roundToInt().coerceAtLeast(1), true)
            val px = IntArray(s.width * s.height).also { s.getPixels(it, 0, s.width, 0, 0, s.width, s.height) }
            Triple(px, s.width to s.height, k).also { if (s !== b) s.recycle() }
        }
        val (px, size, k) = probe(bmp)
        val cut = Deskew.trim(px, size.first, size.second, background)
        if (cut.any { it > 0 }) {
            val l = (cut[0] / k).roundToInt()
            val t = (cut[1] / k).roundToInt()
            val r = (cut[2] / k).roundToInt()
            val b = (cut[3] / k).roundToInt()
            val nw = bmp.width - l - r
            val nh = bmp.height - t - b
            if (nw > bmp.width / 2 && nh > bmp.height / 2) {
                bmp = Bitmap.createBitmap(bmp, l, t, nw, nh).also { if (it !== bmp) bmp.recycle() }
            }
        }
        val (px2, size2, _) = probe(bmp)
        // Corners uncovered by turning are filled with the paper's own colour: pure white beside
        // greyer paper would make the lighting clean-up shade the page next to it.
        val fill = paperColour(px2)
        val angle = Deskew.angle(px2, size2.first, size2.second)
        if (kotlin.math.abs(angle) >= 0.1) {
            val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(out).apply {
                drawColor(fill)
                rotate(-angle.toFloat(), bmp.width / 2f, bmp.height / 2f)
                drawBitmap(bmp, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            }
            bmp.recycle()
            bmp = out
        }
        // Level lines whose starts still drift sideways: the columns lean. Shear them upright.
        val (px3, size3, _) = probe(bmp)
        val lean = Deskew.shear(px3, size3.first, size3.second)
        if (lean == 0.0) return mutable(bmp)
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(fill)
            // x' = x - lean * (y - centre)
            translate((lean * bmp.height / 2).toFloat(), 0f)
            skew((-lean).toFloat(), 0f)
            drawBitmap(bmp, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        bmp.recycle()
        return out
    }

    /** The paper: per channel, the median of the brighter half of the pixels. */
    private fun paperColour(px: IntArray): Int {
        val step = max(1, px.size / 20_000)
        val sample = (px.indices step step).map { px[it] }
        val bright = sample.sortedBy { p -> ((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114 }
            .drop(sample.size / 2)
        if (bright.isEmpty()) return android.graphics.Color.WHITE
        fun ch(shift: Int) = bright.map { (it shr shift) and 0xFF }.sorted()[bright.size / 2]
        return android.graphics.Color.rgb(ch(16), ch(8), ch(0))
    }

    /**
     * Colours of the scene just outside [quad] (normalised TL, TR, BR, BL): points a little beyond
     * each side, away from the centre. Empty when the outline fills the photo.
     */
    fun sceneAround(src: Bitmap, quad: List<Float>): List<Int> {
        val w = src.width
        val h = src.height
        val cx = (quad[0] + quad[2] + quad[4] + quad[6]) / 4
        val cy = (quad[1] + quad[3] + quad[5] + quad[7]) / 4
        val out = ArrayList<Int>()
        for (side in 0 until 4) {
            val ax = quad[side * 2]; val ay = quad[side * 2 + 1]
            val bx = quad[(side * 2 + 2) % 8]; val by = quad[(side * 2 + 3) % 8]
            for (i in 1 until 40) {
                val t = i / 40f
                val mx = ax + (bx - ax) * t
                val my = ay + (by - ay) * t
                // 2% and 4% of the way out from the side.
                for (d in floatArrayOf(0.02f, 0.04f)) {
                    val dx = mx - cx; val dy = my - cy
                    val len = hypot(dx, dy).coerceAtLeast(1e-6f)
                    val x = ((mx + dx / len * d) * w).roundToInt()
                    val y = ((my + dy / len * d) * h).roundToInt()
                    if (x in 0 until w && y in 0 until h) out += src.getPixel(x, y)
                }
            }
        }
        return out
    }

    /**
     * [detectPage] for a page taken from a PDF. Usually that is already the page (a scan or an
     * export) and is left whole, since a band printed along its edge is no desk; only a photo that
     * clearly shows its surroundings is cropped.
     */
    fun detectPageInPdf(file: File): List<Float>? = detectPage(file)?.takeIf { quadArea(it) < 0.85f }

    /** Share of the photo covered by a normalised quad (shoelace formula). */
    fun quadArea(q: List<Float>): Float {
        var a = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            a += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1]
        }
        return kotlin.math.abs(a) / 2
    }

    /** Applies [look] to a mutable ARGB bitmap in place. */
    fun applyLook(bmp: Bitmap, look: Look, level: Int = CameraClean.BOOK_LEVEL) {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        CameraClean.apply(px, bmp.width, bmp.height, look, level)
        bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    }

    /**
     * The image the OCR engine reads: grey, with the lighting evened out. Tesseract binarises on
     * its own, and makes far fewer mistakes on an evenly lit page.
     */
    fun forOcr(page: Bitmap): Bitmap {
        val out = page.copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(out.width * out.height)
        out.getPixels(px, 0, out.width, 0, 0, out.width, out.height)
        CameraClean.clean(px, out.width, out.height, 50)
        CameraClean.toGrey(px)
        out.setPixels(px, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    /** Decodes a photo, honouring its EXIF orientation, with the long side at most [maxSide]. */
    fun decode(file: File, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longSide = max(bounds.outWidth, bounds.outHeight)
        require(longSide > 0) { "Unreadable image: ${file.name}" }
        var sample = 1
        while (longSide / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        var bmp = requireNotNull(BitmapFactory.decodeFile(file.path, opts)) { "Unreadable image: ${file.name}" }
        val orientation = runCatching {
            ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        if (!m.isIdentity) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
        }
        return mutable(scaleDown(bmp, maxSide))
    }

    fun scaleDown(bmp: Bitmap, maxSide: Int): Bitmap {
        val longSide = max(bmp.width, bmp.height)
        if (longSide <= maxSide) return bmp
        val s = maxSide.toFloat() / longSide
        val out = Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt().coerceAtLeast(1), (bmp.height * s).roundToInt().coerceAtLeast(1), true)
        if (out !== bmp) bmp.recycle()
        return mutable(out)
    }

    /**
     * Enlarges a page whose long side is under [side] pixels (at most 2×). Tesseract misses fine
     * detail such as decimal points in small print; more pixels per character fixes most of that.
     */
    fun upscaleTo(bmp: Bitmap, side: Int): Bitmap {
        val longSide = max(bmp.width, bmp.height)
        if (longSide >= side * 0.9f) return bmp
        val s = min(2f, side.toFloat() / longSide)
        val out = Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt(), (bmp.height * s).roundToInt(), true)
        if (out !== bmp) bmp.recycle()
        return mutable(out)
    }

    fun rotate(bmp: Bitmap, degrees: Int): Bitmap =
        mutable(Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees.toFloat()) }, true))

    /** Straightens the quadrilateral [quad] (normalised TL, TR, BR, BL) into a rectangle. */
    fun warp(src: Bitmap, quad: List<Float>): Bitmap {
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val p = FloatArray(8) { i -> quad[i].coerceIn(-0.05f, 1.05f) * if (i % 2 == 0) w else h }
        val top = hypot(p[2] - p[0], p[3] - p[1])
        val bottom = hypot(p[4] - p[6], p[5] - p[7])
        val left = hypot(p[6] - p[0], p[7] - p[1])
        val right = hypot(p[4] - p[2], p[5] - p[3])
        val outW = ((top + bottom) / 2).roundToInt().coerceAtLeast(16)
        val outH = ((left + right) / 2).roundToInt().coerceAtLeast(16)
        val dst = floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())
        val m = Matrix()
        if (!m.setPolyToPoly(p, 0, dst, 0, 4)) return src.copy(Bitmap.Config.ARGB_8888, true)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return out
    }

    /**
     * Finds the page in a photo ([PageFinder]: edge and paper-region candidates, verified against
     * what a page looks like). Returns a normalised quad, or null to use the whole photo.
     */
    fun detectPage(file: File): List<Float>? = runCatching {
        val bmp = decode(file, 1200)
        try {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val found = PageFinder.find(px, w, h) ?: return@runCatching null
            found.corners.flatMap { listOf((it.x / w).toFloat().coerceIn(0f, 1f), (it.y / h).toFloat().coerceIn(0f, 1f)) }
        } finally {
            bmp.recycle()
        }
    }.getOrNull()

    /** Splits the crop of a two-page spread into its left and right halves. */
    fun splitQuad(quad: List<Float>?): Pair<List<Float>, List<Float>> {
        val q = quad ?: ScanPage.FULL_QUAD
        val topMid = listOf((q[0] + q[2]) / 2, (q[1] + q[3]) / 2)
        val bottomMid = listOf((q[4] + q[6]) / 2, (q[5] + q[7]) / 2)
        val left = listOf(q[0], q[1]) + topMid + bottomMid + listOf(q[6], q[7])
        val right = topMid + listOf(q[2], q[3], q[4], q[5]) + bottomMid
        return left to right
    }

    private fun mutable(b: Bitmap): Bitmap =
        if (b.isMutable && b.config == Bitmap.Config.ARGB_8888) b
        else b.copy(Bitmap.Config.ARGB_8888, true).also { if (it !== b) b.recycle() }
}
