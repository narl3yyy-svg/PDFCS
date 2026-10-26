package com.narl3yyy.pdfcs.core

/** Parses page ranges as people type them: "1-3, 5, 8-" (1-based, inclusive; "8-" = to the end). */
object PageRanges {
    /** 0-based page indices in the order given, without repeats; null when the text is invalid. */
    fun parse(text: String, pageCount: Int): List<Int>? {
        if (pageCount <= 0) return null
        val out = LinkedHashSet<Int>()
        val parts = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        for (part in parts) {
            val m = Regex("""^(\d*)\s*[-–]\s*(\d*)$""").find(part)
            if (m != null) {
                val a = m.groupValues[1].ifEmpty { "1" }.toIntOrNull() ?: return null
                val b = m.groupValues[2].ifEmpty { pageCount.toString() }.toIntOrNull() ?: return null
                if (a < 1 || b < 1 || a > pageCount || b > pageCount) return null
                val range = if (a <= b) a..b else a downTo b
                range.forEach { out += it - 1 }
            } else {
                val n = part.toIntOrNull() ?: return null
                if (n !in 1..pageCount) return null
                out += n - 1
            }
        }
        return out.toList()
    }

    /** "1–3, 5" for a set of 0-based pages. */
    fun format(pages: Collection<Int>): String {
        val sorted = pages.toSortedSet().toList()
        if (sorted.isEmpty()) return ""
        val parts = ArrayList<String>()
        var start = sorted[0]
        var prev = start
        for (p in sorted.drop(1) + Int.MIN_VALUE) {
            if (p == prev + 1) { prev = p; continue }
            parts += if (start == prev) "${start + 1}" else "${start + 1}–${prev + 1}"
            start = p
            prev = p
        }
        return parts.joinToString(", ")
    }
}
