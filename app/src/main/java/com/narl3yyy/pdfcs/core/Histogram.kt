package com.narl3yyy.pdfcs.core

/** Grey-level statistics shared by the camera, OCR and page-rebuild code. */
object Histogram {
    /** Otsu's threshold: the grey level that best separates a two-tone histogram (ink and paper). */
    fun otsu(hist: IntArray): Int {
        require(hist.size == 256) { "histogram must have 256 bins" }
        val total = hist.sum().toDouble()
        if (total == 0.0) return 128
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i].toDouble()
        var sumB = 0.0
        var wB = 0.0
        var best = -1.0
        var threshold = 128
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += i * hist[i].toDouble()
            val d = sumB / wB - (sum - sumB) / wF
            val between = wB * wF * d * d
            if (between > best) { best = between; threshold = i }
        }
        return threshold
    }

    /** The grey level below which the share [q] (0..1) of the counted pixels lie. */
    fun percentile(hist: IntArray, q: Double): Int {
        val n = hist.sum().toLong()
        if (n == 0L) return 255
        var acc = 0L
        for (v in hist.indices) {
            acc += hist[v]
            if (acc >= n * q) return v
        }
        return hist.lastIndex
    }

    /** Luminance (ITU-R BT.601, integer) of an ARGB colour. */
    fun luminance(argb: Int): Int =
        (((argb shr 16) and 0xFF) * 299 + ((argb shr 8) and 0xFF) * 587 + (argb and 0xFF) * 114) / 1000
}

/** Small statistics helpers. */
object Stats {
    fun median(v: Collection<Float>): Float? = v.sorted().let { it.getOrNull(it.size / 2) }

    /** Median of values weighted by their counts. */
    fun weightedMedian(v: List<Pair<Float, Int>>): Float? {
        val sorted = v.filter { it.second > 0 }.sortedBy { it.first }
        if (sorted.isEmpty()) return null
        val half = sorted.sumOf { it.second } / 2
        var acc = 0
        for ((value, weight) in sorted) {
            acc += weight
            if (acc > half) return value
        }
        return sorted.last().first
    }
}
