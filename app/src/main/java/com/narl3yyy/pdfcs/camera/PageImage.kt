package com.narl3yyy.pdfcs.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import com.narl3yyy.pdfcs.core.Pt
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
        page.quad?.let { q -> bmp = warp(bmp, q).also { bmp.recycle() } }
        if (page.rotation % 360 != 0) bmp = rotate(bmp, page.rotation).also { bmp.recycle() }
        bmp = scaleDown(bmp, maxSide)
        if (look != Look.ORIGINAL) applyLook(bmp, look)
        return bmp
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
     * Finds the page in a photo: the sheet's straight edges first ([CameraEdges]), then the
     * largest bright region ([PaperRegion]). Returns a normalised quad, or null to use the whole
     * photo.
     */
    fun detectPage(file: File): List<Float>? = runCatching {
        val bmp = decode(file, 1200)
        try {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val corners: List<Pt>? = CameraEdges.detect(px, w, h)
            val quad = corners?.flatMap { listOf((it.x / w).toFloat().coerceIn(0f, 1f), (it.y / h).toFloat().coerceIn(0f, 1f)) }
                ?: PaperRegion.detect(px, w, h)
            // A page that already fills the photo needs no crop.
            quad?.takeUnless { q -> (0 until 4).all { i -> q[i * 2] !in 0.03f..0.97f && q[i * 2 + 1] !in 0.03f..0.97f } }
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
