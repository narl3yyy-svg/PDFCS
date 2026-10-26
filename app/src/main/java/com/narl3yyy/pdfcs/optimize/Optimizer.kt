package com.narl3yyy.pdfcs.optimize

import android.graphics.Bitmap
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlin.math.max
import kotlin.math.roundToInt

/** How hard to compress. */
enum class Reduction(val label: String, val maxPixels: Int, val quality: Float) {
    LIGHT("Light (keeps detail)", 3000, 0.85f),
    MEDIUM("Medium", 2000, 0.75f),
    STRONG("Strong (smallest)", 1400, 0.6f),
}

/**
 * The PDF Optimizer (after pdfcraft-optimize): large photographic images are downsampled and
 * recompressed as JPEG. Images with transparency, masks, or few bits per pixel (line art, text
 * scans) are left alone, so nothing visibly breaks. Returns how many images were recompressed.
 */
object Optimizer {
    fun reduce(doc: PDDocument, level: Reduction): Int {
        val done = HashSet<Any>()
        var count = 0
        for (page in doc.pages) count += reduce(doc, page.resources ?: continue, level, done, 0)
        return count
    }

    private fun reduce(doc: PDDocument, res: PDResources, level: Reduction, done: MutableSet<Any>, depth: Int): Int {
        if (depth > 8) return 0
        var count = 0
        for (name in res.xObjectNames.toList()) {
            val x = runCatching { res.getXObject(name) }.getOrNull() ?: continue
            if (!done.add(x.cosObject)) continue
            when (x) {
                is PDFormXObject -> x.resources?.let { count += reduce(doc, it, level, done, depth + 1) }
                is PDImageXObject -> {
                    if (x.isStencil || x.bitsPerComponent < 8 || x.softMask != null || x.mask != null) continue
                    if (x.cosObject.containsKey(COSName.SMASK) || x.cosObject.containsKey(COSName.MASK)) continue
                    val longSide = max(x.width, x.height)
                    if (longSide <= level.maxPixels && x.suffix == "jpg") continue
                    val bmp = runCatching { x.image }.getOrNull() ?: continue
                    val scaled = if (longSide > level.maxPixels) {
                        val s = level.maxPixels.toFloat() / longSide
                        Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt().coerceAtLeast(1), (bmp.height * s).roundToInt().coerceAtLeast(1), true)
                    } else bmp
                    val img = JPEGFactory.createFromImage(doc, scaled, level.quality)
                    // Only keep the new image when it is actually smaller.
                    if (img.cosObject.length < x.cosObject.length) { res.put(name, img); count++ }
                    if (scaled !== bmp) scaled.recycle()
                    bmp.recycle()
                }
            }
        }
        return count
    }
}
