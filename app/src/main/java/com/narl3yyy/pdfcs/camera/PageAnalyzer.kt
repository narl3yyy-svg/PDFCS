package com.narl3yyy.pdfcs.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.narl3yyy.pdfcs.core.Pt
import kotlin.math.max

/**
 * Finds the page in camera preview frames for the live guide ([LiveGuide]): each frame is turned
 * upright like the photo will be, made small and searched with the same [PageFinder] the photos
 * get. [onFrame] receives the corners normalised to the upright frame, or null.
 */
class PageAnalyzer(private val onFrame: (List<Pt>?) -> Unit) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        try {
            val frame = image.toBitmap()
            val k = SIDE.toFloat() / max(frame.width, frame.height)
            val m = Matrix().apply {
                postScale(k, k)
                postRotate(image.imageInfo.rotationDegrees.toFloat())
            }
            val small = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, m, true)
            if (small !== frame) frame.recycle()
            val w = small.width
            val h = small.height
            val px = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
            small.recycle()
            onFrame(PageFinder.find(px, w, h)?.corners?.let { LiveGuide.normalised(it, w, h) })
        } catch (_: Throwable) {
            onFrame(null)
        } finally {
            image.close()
        }
    }

    companion object {
        /** Long side of the searched frame: enough for a page's edges, quick to search. */
        const val SIDE = 480
    }
}
