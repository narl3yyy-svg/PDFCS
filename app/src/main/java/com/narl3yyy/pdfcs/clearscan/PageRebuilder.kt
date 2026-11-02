package com.narl3yyy.pdfcs.clearscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.narl3yyy.pdfcs.core.Stats
import com.narl3yyy.pdfcs.library.FontChoice
import com.narl3yyy.pdfcs.ocr.CaseFix
import com.narl3yyy.pdfcs.ocr.OcrEngine
import com.narl3yyy.pdfcs.ocr.OcrLine
import com.narl3yyy.pdfcs.ocr.OcrWord
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Rebuilds a photographed page as a born-digital page (like Acrobat's "ClearScan"):
 *
 * 1. Tesseract reads the page (lines, words, boxes, baselines).
 * 2. The typeface family that fits the page is chosen (serif or sans), and every word is set in
 *    it at the size its real glyph extents give, stretched to the word's width.
 * 3. Weight (bold) and slant (italic) are measured per line from the ink itself.
 * 4. Every word is typeset and compared with the ink. Only words whose rendering matches the
 *    photo are printed as text; a doubtful word gets a second, enlarged reading and typographic
 *    look-alikes, and if still nothing matches it keeps its original pixels, with invisible text
 *    on top so it stays searchable. A misreading is therefore never printed.
 * 5. Each printed line gets one baseline and one size, a page few sizes, and line starts and ends
 *    the margins they share. Words kept as pixels move and size with their line.
 * 6. What is left of the ink (figures, photos, rules, logos) is cut out as pictures; white text on
 *    dark bands is read inverted so it is searchable too.
 *
 * One instance serves one document: it learns the document's typeface and regular text weight as
 * it goes. Where each page goes on paper is settled once all pages are read ([PageLayout]).
 */
class PageRebuilder(context: Context, private val ocr: OcrEngine, private val fontChoice: FontChoice) {
    private val fonts = FontLibrary.get(context)

    /** Typical ink weight of regular body text on earlier pages. */
    private val bodyWeights = ArrayList<Float>()

    fun rebuild(colour: Bitmap, gray: Bitmap): RebuiltPage {
        val lines = dropMarginJunk(ocr.readPage(gray), gray.width, gray.height)
        val ink = InkMap(gray)
        val fitter = WordFitter(ink, colour)

        val family = when (fontChoice) {
            FontChoice.SERIF -> FontFamily.SERIF
            FontChoice.SANS -> FontFamily.SANS
            FontChoice.AUTO -> documentFamily(lines, fitter)
        }
        val regular = fonts.face(family, FontStyle.REGULAR)

        // Fit every word in the regular face; measure each line's weight and slant.
        val lineSizes = ArrayList<Pair<Float, Int>>()
        val fitted = lines.mapNotNull { line ->
            val size = fitter.lineSize(line, regular) ?: return@mapNotNull null
            lineSizes += size to line.words.size
            val words = line.words.mapIndexed { i, word -> bestCase(word, line, i, regular, size, fitter) }
            LineFit(line, size, words, fitter.slant(line))
        }
        val pageWeight = bodyWeight(fitted, fitter)

        var verified = 0
        val perLine = fitted.map { decideLine(it, pageWeight, family, fitter) }
        rejectOddWidths(perLine)
        val budget = intArrayOf(MAX_REREADS)
        val decidedLines = perLine.map { line ->
            alignBaselines(line.map { d ->
                if (d.visible || budget[0] <= 0) d
                else secondLook(d, gray, fitter, budget)?.also { verified++ } ?: d
            })
        }
        val decided = decidedLines.flatten()
        val bodySize = Stats.weightedMedian(lineSizes) ?: 40f

        // Words kept as pixels in lines of text are cut out one by one, so they can move and size
        // with their line instead of sitting where the photo had them between typeset words.
        val visibleBoxes = decided.filter { it.visible }.map { it.p.box }
        // Each kept word's placement before the lines are aligned: its pixels move as it does.
        val before = decided.filter { !it.visible }.associateWith { it.p }
        val kept = ArrayList<Triple<Decision, Placement, Graphic>>()
        for (d in alignRows(decidedLines, bodySize)) {
            if (d.p.box.height() > bodySize * 2.5f) continue
            val r = expand(d.p.box, max(1, (d.p.box.height() * 0.08f).roundToInt()))
            r.intersect(0, 0, ink.w, ink.h)
            if (r.isEmpty) continue
            cut(colour, r, visibleBoxes, word = true)?.let { g -> kept += Triple(d, before.getValue(d), g); ink.erase(r) }
        }
        for ((d, before, g) in kept) {
            val p = d.p
            val ky = p.size / before.size
            val kx = p.size * p.scale / (before.size * before.scale)
            g.dest = RectF(
                p.x + (g.rect.left - before.x) * kx, p.baseline + (g.rect.top - before.baseline) * ky,
                p.x + (g.rect.right - before.x) * kx, p.baseline + (g.rect.bottom - before.baseline) * ky,
            )
        }

        // White text on dark bands: read inverted, kept as pixels with searchable text on top.
        val words = ArrayList<PageWord>()
        for (d in decided) {
            val colourOfInk = if (d.visible) fitter.inkColour(d.p.box) else Color.BLACK
            words += PageWord(d.p.text, d.p.face, d.p.size, d.p.scale, d.p.x, d.p.baseline, d.visible, colourOfInk)
            if (d.visible) ink.erase(expand(d.p.box, max(2, (d.p.box.height() * 0.12f).roundToInt())))
        }
        val panels = GraphicsFinder.darkPanels(ink, bodySize)
        val invertedBoxes = ArrayList<Rect>()
        for (r in panels) {
            for (line in ocr.readInverted(gray, r)) {
                val size = fitter.lineSize(line, regular) ?: continue
                line.words.forEachIndexed { i, w ->
                    invertedBoxes += w.box
                    fitter.place(w, wordText(line, i), regular, size, line)?.let {
                        words += PageWord(it.text, it.face, it.size, it.scale, it.x, it.baseline, false, Color.BLACK)
                    }
                }
            }
        }
        // Graphics are what is left of the ink; ink at the edge holding no words is background.
        val regions = GraphicsFinder.find(ink, bodySize, lines.flatMap { l -> l.words.map { it.box } } + invertedBoxes)
        val graphics = kept.map { it.third } + regions.mapNotNull { r -> cut(colour, r, visibleBoxes) }

        val stats = RebuiltPage.Stats(decided.count { it.visible }, decided.count { !it.visible }, verified, family)
        Log.i(TAG, "page: ${stats.textWords} text words, ${stats.imageWords} kept as image (${kept.size} in lines), " +
            "$verified verified, ${graphics.size} graphics, family=$family, body=${"%.1f".format(bodySize)}px")
        val content = Rect()
        for (d in decided) content.union(d.p.box)
        for (g in graphics) content.union(g.rect)
        val bodyLines = lineSizes.count { (size, n) -> n >= 3 && abs(size / bodySize - 1f) < 0.15f }
        val measure = PageLayout.Measure(
            ink.w, ink.h,
            if (content.isEmpty) null else intArrayOf(content.left, content.top, content.right, content.bottom),
            if (bodyLines >= 4) bodySize else null,
        )
        return RebuiltPage(ink.w, ink.h, words, graphics, stats, measure)
    }

    // ------------------------------------------------------------------------------------------

    private class Decision(var p: Placement, var visible: Boolean)

    private class LineFit(val line: OcrLine, val size: Float, val words: List<Pair<Placement, Match>?>, val slant: Float) {
        val inkAmount: Int = words.sumOf { it?.second?.photo ?: 0 }
        /** Photo ink relative to regular-type ink, over the whole line. */
        val weight: Float? = words.sumOf { it?.second?.rendered ?: 0 }.takeIf { it > 0 }?.let { inkAmount.toFloat() / it }
    }

    private fun wordText(line: OcrLine, i: Int) = spaced(line, i, CaseFix.fix(FontLibrary.normalize(line.words[i].text)))

    private fun spaced(line: OcrLine, i: Int, text: String) = text + if (i < line.words.lastIndex) " " else ""

    /**
     * The word set in [face], with its case put right: a word read in a case print never uses
     * ("ReqUIrements") is set in each case it may really have, and the one whose glyphs match the
     * ink best is kept (capitals and small letters have different shapes).
     */
    private fun bestCase(word: OcrWord, line: OcrLine, i: Int, face: FontFace, size: Float, fitter: WordFitter): Pair<Placement, Match>? {
        val read = FontLibrary.normalize(word.text)
        val options = CaseFix.candidates(read).ifEmpty { listOf(read) }
        var best: Pair<Placement, Match>? = null
        for (text in options) {
            val p = fitter.place(word, spaced(line, i, text), face, size, line) ?: continue
            val m = fitter.match(p)
            // In order of likelihood: a later spelling must fit clearly better.
            if (best == null || m.fit > best.second.fit + CANDIDATE_MARGIN) best = p to m
        }
        if (options.size > 1 && best != null && best.first.ink != options[0]) Log.d(TAG, "case '$read' -> '${best.first.ink}'")
        return best
    }

    /**
     * Camera blur thickens every stroke, so weight is judged against this document's own body text
     * (assumed regular): lines of several words printed on plain paper. Pages with little body text
     * (figure pages, title pages) borrow the document's figure, or the page's lightest text.
     */
    private fun bodyWeight(fitted: List<LineFit>, fitter: WordFitter): Float {
        val onPaper = fitted.filter { f -> f.line.words.count { fitter.onPaper(it.box) } * 2 > f.line.words.size }
        val body = onPaper.filter { it.line.words.size >= 3 }
        val w = when {
            body.size >= 6 -> Stats.weightedMedian(body.mapNotNull { f -> f.weight?.let { it to f.line.words.size } })?.also { bodyWeights += it }
            bodyWeights.isNotEmpty() -> Stats.median(bodyWeights)
            else -> onPaper.mapNotNull { it.weight }.sorted().let { it.getOrNull(it.size / 4) }
        }
        return (w ?: 1f).coerceAtLeast(0.05f)
    }

    private fun decideLine(f: LineFit, pageWeight: Float, family: FontFamily, fitter: WordFitter): List<Decision> {
        val lineBold = (f.weight ?: 0f) / pageWeight > BOLD_RATIO
        val lineItalic = f.slant > ITALIC_SLANT
        return f.line.words.mapIndexedNotNull { i, word ->
            val (reg, regMatch) = f.words[i] ?: return@mapIndexedNotNull invisible(word, f, i, family, fitter)
            // A word may differ from its line when its own ink says so clearly (real emphasis).
            val wordWeight = if (regMatch.rendered > 0) regMatch.photo.toFloat() / regMatch.rendered / pageWeight else 1f
            // Short words carry too little ink to judge on their own.
            val own = when {
                reg.ink.count(Char::isLetterOrDigit) < 4 -> null
                wordWeight > BOLD_RATIO + 0.3f -> true
                wordWeight < BOLD_RATIO - 0.25f -> false
                else -> null
            }
            val bold = own ?: lineBold
            val style = when {
                bold && lineItalic -> FontStyle.BOLD_ITALIC
                bold -> FontStyle.BOLD
                lineItalic -> FontStyle.ITALIC
                else -> FontStyle.REGULAR
            }
            var p = reg
            var fit = regMatch.fit
            if (style != FontStyle.REGULAR) {
                fitter.place(word, reg.text, fonts.face(family, style), f.size, f.line)?.let { alt ->
                    val m = fitter.fit(alt)
                    // The whole line's evidence: a style a little worse than regular in fit is
                    // still taken. A single word against its line must fit clearly better, or
                    // camera blur on one word would scatter bold words through the text.
                    val need = if (own != null && own != lineBold) fit + 0.02f else fit - 0.06f
                    if (m > need) { p = alt; fit = m }
                }
            }
            Decision(p, acceptable(word, p, fit, fitter))
        }
    }

    /**
     * A word is printed when its typeset form matches the ink. A word the OCR is very sure of may
     * fit a little less (the page's typeface is not one of the bundled ones); words kept as pixels
     * between typeset ones make a page look patched.
     */
    private fun acceptable(word: OcrWord, p: Placement, fit: Float, fitter: WordFitter): Boolean {
        val short = p.ink.length <= 2
        val common = p.ink.trim { !it.isLetter() }.lowercase() in COMMON_WORDS
        // Numbers never get the benefit of the doubt: one wrong digit (a date, a dimension, a
        // clause number) changes what a document says, and digits look alike in any typeface.
        val digits = p.ink.any(Char::isDigit)
        val minFit = when {
            common && word.confidence >= SURE_CONFIDENCE -> MIN_FIT_COMMON
            short -> MIN_FIT_SHORT
            digits -> MIN_FIT
            word.confidence >= CERTAIN_CONFIDENCE -> MIN_FIT_CERTAIN
            word.confidence >= SURE_CONFIDENCE -> MIN_FIT_SURE
            else -> MIN_FIT
        }
        return word.confidence >= MIN_CONFIDENCE && fit >= minFit && p.scale in 0.6f..1.7f &&
            word.box.height() >= 5 && fitter.onPaper(word.box)
    }

    /**
     * One baseline per line: the words of a line are set on the line's median baseline, as type is.
     * A word far off it (a superscript, a word of another column the OCR joined) keeps its own.
     */
    private fun alignBaselines(line: List<Decision>): List<Decision> {
        val visible = line.filter { it.visible }
        if (visible.size < 2) return line
        val base = Stats.median(visible.map { it.p.baseline }) ?: return line
        // Typeset words only: a kept word's placement must stay where its pixels are until the
        // lines are aligned, which moves both together.
        for (d in visible) {
            val p = d.p
            if (abs(p.baseline - base) < p.size * 0.18f && p.baseline != base) d.p = p.copy(baseline = base)
        }
        return line
    }

    /**
     * Pattern left of the scene along a side (the stripes of a cloth) reads as "|", "l" or "!".
     * Such one- or two-character readings, out in the outer margin beyond all the real text, are
     * no words: they are dropped, so they are neither drawn nor searchable.
     */
    private fun dropMarginJunk(lines: List<OcrLine>, w: Int, h: Int): List<OcrLine> {
        val junk = Regex("""^[|!lI1i'`".,:;_~\-]{1,2}$""")
        val real = lines.flatMap { it.words }.filter { it.text.count(Char::isLetterOrDigit) >= 2 }
        if (real.isEmpty()) return lines
        val content = Rect(real.first().box).apply { real.forEach { union(it.box) } }
        val mx = w * 0.06f
        val my = h * 0.06f
        fun inMargin(r: Rect) = (r.right <= content.left && r.left < mx) || (r.left >= content.right && r.right > w - mx) ||
            (r.bottom <= content.top && r.top < my) || (r.top >= content.bottom && r.bottom > h - my)
        return lines.mapNotNull { line ->
            val kept = line.words.filterNot { junk.matches(it.text) && inMargin(it.box) }
            when {
                kept.isEmpty() -> null
                kept.size == line.words.size -> line
                else -> line.copy(words = kept)
            }
        }
    }

    /**
     * One baseline and one size per printed line, few sizes per page, and flush margins. The OCR
     * sometimes cuts a printed line into pieces (a page a little curved, a wide gap), and each
     * piece gets its own baseline and size, so the rebuilt line would step up and down; a photo
     * taken a little at an angle leaves the line starts drifting. So:
     *
     * - pieces at the same height that sit side by side (not across a column gap) become one line,
     *   set on its median baseline at its median size. Superscripts and much larger or smaller
     *   words keep theirs;
     * - a page is set in few sizes: line sizes within [SIZE_STEP] of each other are one size, and
     *   one near the body text is the body size;
     * - line starts and ends close to a margin shared by several lines are moved onto it, a line
     *   reaching both margins (justified text) is stretched between them.
     *
     * Words kept as pixels go along with their line: returned are those in lines of mostly
     * typeset words, whose pixels the caller moves to match.
     */
    private fun alignRows(lines: List<List<Decision>>, bodySize: Float): List<Decision> {
        class Row(val words: MutableList<Decision>) {
            var left = 0; var right = 0; var base = 0f; var size = 0f
            fun measure() = apply {
                // Typeset words say where the line is; kept words only when it has none.
                val ref = words.filter { it.visible }.ifEmpty { words }
                left = words.minOf { it.p.box.left }
                right = words.maxOf { it.p.box.right }
                base = Stats.median(ref.map { it.p.baseline })!!
                size = Stats.median(ref.map { it.p.size })!!
            }
        }
        val rows = lines.filter { it.isNotEmpty() }.map { Row(it.toMutableList()).measure() }.toMutableList()
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in rows.indices) for (j in i + 1 until rows.size) {
                val a = rows[i]
                val b = rows[j]
                val size = min(a.size, b.size)
                val gap = max(a.left, b.left) - min(a.right, b.right)
                if (abs(a.base - b.base) < 0.4f * size && gap > 0 && gap < 3f * size) {
                    a.words += b.words
                    a.measure()
                    rows.removeAt(j)
                    merged = true
                    break@loop
                }
            }
        }
        // Only lines with typeset words are aligned; a line of kept words only stays as it is.
        rows.removeAll { r -> r.words.none { it.visible } }
        class Target(val row: Row, val peers: List<Decision>, val size: Float, val base: Float)
        val targets = rows.mapNotNull { row ->
            row.measure()
            val peers = row.words.filter { it.p.size / row.size in 0.8f..1.25f }
            val ref = peers.filter { it.visible }.ifEmpty { peers }
            if (ref.isEmpty()) return@mapNotNull null
            val size = Stats.weightedMedian(ref.map { it.p.size to it.p.box.width() }) ?: return@mapNotNull null
            val base = Stats.weightedMedian(ref.map { it.p.baseline to it.p.box.width() }) ?: return@mapNotNull null
            Target(row, peers, size, base)
        }
        val sizes = typeSizes(targets.map { it.size to it.row.words.size }, bodySize)
        targets.forEachIndexed { i, t ->
            val size = sizes[i]
            for (d in t.peers) {
                val p = d.p
                // A word far off the line (a superscript) keeps its place. So does a kept word
                // well off it: its OCR box may not be where its pixels are, and moving pixels far
                // on a guess could lay them over the next line.
                if (abs(p.baseline - t.base) >= 0.3f * size) continue
                val newSize = if (abs(p.size / size - 1f) < 0.2f) size else p.size
                // The same width at the new size: the stretch makes up for it.
                d.p = p.copy(size = newSize, scale = p.scale * p.size / newSize, baseline = t.base)
            }
            t.row.size = size
        }
        // Margins: edges shared by at least three lines, each the median of its group.
        fun margins(edges: List<Int>): List<Float> {
            val sorted = edges.sorted()
            val out = ArrayList<Float>()
            var start = 0
            for (i in 1..sorted.size) {
                if (i == sorted.size || sorted[i] - sorted[i - 1] > 0.35f * bodySize) {
                    val group = sorted.subList(start, i)
                    if (group.size >= 3) out += group[group.size / 2].toFloat()
                    start = i
                }
            }
            return out
        }
        val lefts = margins(rows.map { it.left })
        val rights = margins(rows.map { it.right })
        for (row in rows) {
            val l = row.left.toFloat()
            val r = row.right.toFloat()
            val tol = 0.7f * row.size
            val newL = lefts.minByOrNull { abs(it - l) }?.takeIf { abs(it - l) < tol } ?: l
            val newR = rights.minByOrNull { abs(it - r) }?.takeIf { abs(it - r) < tol } ?: (r + newL - l)
            if (newL == l && newR == r) continue
            val f = if (r > l) (newR - newL) / (r - l) else 1f
            if (f !in 0.97f..1.03f) continue
            for (d in row.words) {
                val p = d.p
                d.p = p.copy(scale = p.scale * f, x = newL + (p.x - l) * f)
            }
        }
        return rows.filter { r -> r.words.count { it.visible } * 3 >= r.words.size }.flatMap { r -> r.words.filter { !it.visible } }
    }

    /**
     * Type sizes of a page's lines ([sizes]: size and word count): sizes within [SIZE_STEP] of
     * each other become their weighted median, as a typeset page uses a few sizes; a size close
     * to [bodySize] is the body size.
     */
    private fun typeSizes(sizes: List<Pair<Float, Int>>, bodySize: Float): List<Float> {
        val order = sizes.indices.sortedBy { sizes[it].first }
        val out = FloatArray(sizes.size)
        var start = 0
        while (start < order.size) {
            var end = start + 1
            while (end < order.size && sizes[order[end]].first <= sizes[order[start]].first * (1f + SIZE_STEP)) end++
            val group = order.subList(start, end)
            var size = Stats.weightedMedian(group.map { sizes[it] })!!
            if (abs(size / bodySize - 1f) < SIZE_STEP) size = bodySize
            for (i in group) out[i] = size
            start = end
        }
        return out.toList()
    }

    /** Searchable but not drawn, for characters the bundled fonts don't have. */
    private fun invisible(word: OcrWord, f: LineFit, i: Int, family: FontFamily, fitter: WordFitter): Decision? {
        val face = fonts.face(family, FontStyle.REGULAR)
        val text = wordText(f.line, i).filter { face.supports(it.code) }
        if (text.isBlank()) return null
        return fitter.place(word, text, face, f.size, f.line)?.let { Decision(it, false) }
    }

    /**
     * Text set in the right font has nearly the same horizontal scale across a line. A word that
     * needs much more or less stretch than its neighbours is probably misread (a lost decimal
     * point, merged letters), so it keeps its original pixels instead.
     */
    private fun rejectOddWidths(lines: List<List<Decision>>) {
        fun ref(ds: List<Decision>) = Stats.median(ds.filter { it.visible && it.p.ink.length >= 3 }.map { it.p.scale })
        val page = ref(lines.flatten()) ?: return
        for (ds in lines) {
            val r = ref(ds) ?: page
            for (d in ds) if (d.visible && d.p.scale / r !in 0.84f..1.19f) d.visible = false
        }
    }

    /**
     * A second look at a word that failed its checks: read it again on its own, enlarged, and try
     * the usual typographic look-alikes. Each reading is typeset and compared with the ink; one
     * that matches the page is printed. Candidates are in order of likelihood, so a later one must
     * fit clearly better.
     */
    private fun secondLook(d: Decision, gray: Bitmap, fitter: WordFitter, budget: IntArray): Decision? {
        val p = d.p
        if (p.box.height() < 6 || !fitter.onPaper(p.box)) return null
        budget[0]--
        val trailing = if (p.text.endsWith(" ")) " " else ""
        val candidates = LinkedHashSet<String>()
        candidates += p.ink
        ocr.readWord(gray, p.box)?.let { candidates += FontLibrary.normalize(it) }
        candidates += lookAlikes(p.ink)
        var best: Placement? = null
        var bestFit = 0f
        for (c in candidates) {
            if (c.isBlank() || !p.face.supportsAll(c)) continue
            val q = fitter.place(p.word, c + trailing, p.face, p.lineSize, p.line) ?: continue
            if (q.scale !in 0.6f..1.7f) continue
            val fit = fitter.fit(q)
            if (best == null || fit > bestFit + CANDIDATE_MARGIN) { bestFit = fit; best = q }
        }
        val b = best ?: return null
        val minFit = if (b.ink.length <= 2) MIN_FIT_SHORT else MIN_FIT
        if (bestFit < minFit) return null
        if (b.ink != p.ink) Log.d(TAG, "second look '${p.ink}' -> '${b.ink}' fit=${"%.2f".format(bestFit)}")
        return Decision(b, true)
    }

    /** Characters OCR commonly confuses with typographic symbols it rarely outputs. */
    private fun lookAlikes(text: String): List<String> {
        val out = ArrayList<String>()
        if (text.length == 1) out += listOf("•", "–", "—", "-", "·", "°")
        if ('?' in text) out += text.replace('?', '²')
        return out
    }

    /** How well each family fits the document's pages so far, summed. */
    private val familyFit = HashMap<FontFamily, Double>()

    /**
     * One typeface for the whole document, as it was printed in one: each page adds how well each
     * family fits its words, and the family fitting best so far is used.
     */
    private fun documentFamily(lines: List<OcrLine>, fitter: WordFitter): FontFamily {
        for ((fam, v) in familyScores(lines, fitter)) familyFit[fam] = (familyFit[fam] ?: 0.0) + v
        return familyFit.maxByOrNull { it.value }?.takeIf { it.value > 0.0 }?.key ?: FontFamily.SERIF
    }

    private fun familyScores(lines: List<OcrLine>, fitter: WordFitter): Map<FontFamily, Double> {
        val all = lines.flatMap { l -> l.words.map { l to it } }.filter { (_, w) -> w.confidence >= 85 && w.text.length >= 3 }
        val sample = if (all.size <= 60) all else all.filterIndexed { i, _ -> i % (all.size / 60 + 1) == 0 }
        if (sample.isEmpty()) return emptyMap()
        return FontFamily.entries.associateWith { fam ->
            sample.sumOf { (line, w) ->
                val size = fitter.lineSize(line, fonts.face(fam, FontStyle.REGULAR)) ?: return@sumOf 0.0
                val t = FontLibrary.normalize(w.text)
                listOf(FontStyle.REGULAR, FontStyle.BOLD).maxOf { st ->
                    fitter.place(w, t, fonts.face(fam, st), size, line)?.let { fitter.fit(it) } ?: 0f
                }.toDouble()
            }
        }
    }

    /**
     * Cuts a region out of the cleaned colour page; words typeset on top are whited out underneath.
     * A [word] kept as pixels is never a photo (its soft edges are most of a small crop), so it is
     * always cleaned to ink on white paper like the typeset words beside it.
     */
    private fun cut(colour: Bitmap, r: Rect, typeset: List<Rect>, word: Boolean = false): Graphic? = runCatching {
        val crop = Bitmap.createBitmap(colour, r.left, r.top, r.width(), r.height()).copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(crop)
        val white = Paint().apply { color = Color.WHITE }
        for (b in typeset) if (Rect.intersects(b, r)) {
            val e = expand(b, 1)
            c.drawRect((e.left - r.left).toFloat(), (e.top - r.top).toFloat(), (e.right - r.left).toFloat(), (e.bottom - r.top).toFloat(), white)
        }
        val w = crop.width
        val row = IntArray(w)
        var mid = 0L
        var coloured = 0L
        val hist = IntArray(256)
        for (y in 0 until crop.height) {
            crop.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val px = row[x]
                val lo = minOf(Color.red(px), Color.green(px), Color.blue(px))
                hist[lo]++
                if (lo < 180 && maxOf(Color.red(px), Color.green(px), Color.blue(px)) - lo > 50) coloured++
                if (lo > 215) row[x] = Color.WHITE else if (lo in 60..200) mid++
            }
            crop.setPixels(row, 0, w, 0, y, w, 1)
        }
        val photographic = !word && mid > w.toLong() * crop.height / 4
        // Words and drawings kept as pixels on paper the lighting left grey (a shadow, the curve
        // towards the spine): the paper is made white like the rest of the page, so they don't
        // sit in grey boxes. The paper is the brightest tenth of the region. Black print (words,
        // rules, line art without colour) is also made black again, as dark as the typeset text
        // next to it, instead of the camera's soft grey.
        if (!photographic) {
            val total = w.toLong() * crop.height
            var acc = 0L
            var paper = 255
            // A word's crop is mostly ink, so its paper is its brightest pixels, not its brightest tenth.
            val share = if (word) 100 else 10
            for (v in 255 downTo 0) { acc += hist[v]; if (acc * share >= total) { paper = v; break } }
            val cutoff = if (paper in 120..215) paper - 30 else 215
            var dark = 0L
            for (v in 0 until cutoff) dark += hist[v]
            acc = 0L
            var ink = 0
            for (v in 0..255) { acc += hist[v]; if (acc * 20 >= dark) { ink = v; break } }
            val inkOnly = dark > 0 && coloured * 10 < dark && ink < cutoff - 40
            if (cutoff < 215 || inkOnly) {
                // Solid ink to black, paper to white.
                val span = (cutoff - ink).coerceAtLeast(1)
                fun level(v: Int) = ((v - ink) * 255 / span).coerceIn(0, 255)
                for (y in 0 until crop.height) {
                    crop.getPixels(row, 0, w, 0, y, w, 1)
                    for (x in 0 until w) {
                        val px = row[x]
                        val lo = minOf(Color.red(px), Color.green(px), Color.blue(px))
                        row[x] = when {
                            lo > cutoff -> Color.WHITE
                            inkOnly -> Color.rgb(level(Color.red(px)), level(Color.green(px)), level(Color.blue(px)))
                            else -> px
                        }
                    }
                    crop.setPixels(row, 0, w, 0, y, w, 1)
                }
            }
        }
        Graphic(r, crop, photographic)
    }.getOrNull()

    private fun expand(r: Rect, d: Int) = Rect(r.left - d, r.top - d, r.right + d, r.bottom + d)

    companion object {
        private const val TAG = "PageRebuilder"
        private const val MIN_CONFIDENCE = 55
        /** Share of ink that must agree (within a pixel) between the typeset word and the photo. */
        private const val MIN_FIT = 0.80f
        private const val MIN_FIT_SHORT = 0.86f
        /** OCR confidence at which a word may fit its ink less closely ([MIN_FIT_SURE]). */
        private const val SURE_CONFIDENCE = 90
        private const val MIN_FIT_SURE = 0.70f
        private const val CERTAIN_CONFIDENCE = 95
        private const val MIN_FIT_CERTAIN = 0.62f
        /** The commonest words: read with confidence they are what they look like in any typeface. */
        private const val MIN_FIT_COMMON = 0.55f
        private val COMMON_WORDS = (
            "a an and are as at be been but by can do for from had has have he her his if in into is it its " +
                "may more must no not of on or our shall she should so such than that the their them then there " +
                "these they this those to up was we were what when where which while who will with would you"
            ).split(' ').toSet()
        /** Bold type carries about 1.4× the ink of regular type; this is the line between. */
        private const val BOLD_RATIO = 1.25f
        private const val ITALIC_SLANT = 1.03f
        private const val CANDIDATE_MARGIN = 0.03f
        /** Line sizes closer than this (a share) are one type size. */
        private const val SIZE_STEP = 0.08f
        /** At most this many words per page get a second reading (each takes a moment). */
        private const val MAX_REREADS = 160


        /** Languages whose scripts the bundled fonts can typeset; others keep the photo layout. */
        val SUPPORTED_LANGUAGES = setOf(
            "eng", "fra", "deu", "spa", "ita", "por", "nld", "swe", "nor", "dan", "fin", "pol", "ces", "hun",
            "ron", "tur", "ell", "rus", "ukr", "vie", "ind", "lat",
        )
    }
}
