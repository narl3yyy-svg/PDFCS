package com.narl3yyy.pdfcs.clearscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.narl3yyy.pdfcs.core.Stats
import com.narl3yyy.pdfcs.library.FontChoice
import com.narl3yyy.pdfcs.ocr.OcrEngine
import com.narl3yyy.pdfcs.ocr.OcrLine
import com.narl3yyy.pdfcs.ocr.OcrWord
import kotlin.math.max
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
 * 5. What is left of the ink (figures, photos, rules, logos) is cut out as pictures; white text on
 *    dark bands is read inverted so it is searchable too.
 *
 * One instance serves one document (it learns the document's regular text weight as it goes).
 */
class PageRebuilder(context: Context, private val ocr: OcrEngine, private val fontChoice: FontChoice) {
    private val fonts = FontLibrary.get(context)

    /** Typical ink weight of regular body text on earlier pages. */
    private val bodyWeights = ArrayList<Float>()

    fun rebuild(colour: Bitmap, gray: Bitmap): RebuiltPage {
        val lines = ocr.readPage(gray)
        val ink = InkMap(gray)
        val fitter = WordFitter(ink, colour)

        val family = when (fontChoice) {
            FontChoice.SERIF -> FontFamily.SERIF
            FontChoice.SANS -> FontFamily.SANS
            FontChoice.AUTO -> chooseFamily(lines, fitter)
        }
        val regular = fonts.face(family, FontStyle.REGULAR)

        // Fit every word in the regular face; measure each line's weight and slant.
        val lineSizes = ArrayList<Pair<Float, Int>>()
        val fitted = lines.mapNotNull { line ->
            val size = fitter.lineSize(line, regular) ?: return@mapNotNull null
            lineSizes += size to line.words.size
            val words = line.words.mapIndexed { i, word ->
                fitter.place(word, wordText(line, i), regular, size, line)?.let { it to fitter.match(it) }
            }
            LineFit(line, size, words, fitter.slant(line))
        }
        val pageWeight = bodyWeight(fitted, fitter)

        var verified = 0
        val perLine = fitted.map { decideLine(it, pageWeight, family, fitter) }
        rejectOddWidths(perLine)
        val budget = intArrayOf(MAX_REREADS)
        val decided = perLine.flatten().map { d ->
            if (d.visible || budget[0] <= 0) d
            else secondLook(d, gray, fitter, budget)?.also { verified++ } ?: d
        }

        // White text on dark bands: read inverted, kept as pixels with searchable text on top.
        val bodySize = Stats.weightedMedian(lineSizes) ?: 40f
        val words = ArrayList<PageWord>()
        for (d in decided) {
            val colourOfInk = if (d.visible) fitter.inkColour(d.p.box) else Color.BLACK
            words += PageWord(d.p.text, d.p.face, d.p.size, d.p.scale, d.p.x, d.p.baseline, d.visible, colourOfInk)
            if (d.visible) ink.erase(expand(d.p.box, max(2, (d.p.box.height() * 0.12f).roundToInt())))
        }
        val panels = GraphicsFinder.darkPanels(ink, bodySize)
        val regions = GraphicsFinder.find(ink, bodySize, lines.flatMap { l -> l.words.map { it.box } })
        for (r in panels) {
            for (line in ocr.readInverted(gray, r)) {
                val size = fitter.lineSize(line, regular) ?: continue
                line.words.forEachIndexed { i, w ->
                    fitter.place(w, wordText(line, i), regular, size, line)?.let {
                        words += PageWord(it.text, it.face, it.size, it.scale, it.x, it.baseline, false, Color.BLACK)
                    }
                }
            }
        }
        val visibleBoxes = decided.filter { it.visible }.map { it.p.box }
        val graphics = regions.mapNotNull { r -> cut(colour, r, visibleBoxes) }

        // Physical size: body text comes out at about 10 pt, the page between 7 and 14 inches.
        val ppp = pageScale(ink.w, ink.h, bodySize)
        val stats = RebuiltPage.Stats(decided.count { it.visible }, decided.count { !it.visible }, verified, family)
        Log.i(TAG, "page: ${stats.textWords} text words, ${stats.imageWords} kept as image, $verified verified, " +
            "${graphics.size} graphics, family=$family, body=${"%.1f".format(bodySize)}px")
        val (pw, ph) = documentSize?.takeIf { (dw, dh) -> ink.w * ppp <= dw + 0.5f && ink.h * ppp <= dh + 0.5f }
            ?: (ink.w * ppp to ink.h * ppp)
        return RebuiltPage(ink.w, ink.h, ppp, pw, ph, words, graphics, stats)
    }

    // ------------------------------------------------------------------------------------------

    /** The first page's size in points, which later pages of the same shape share. */
    private var documentSize: Pair<Float, Float>? = null

    /**
     * Points per pixel. Body text sets a first guess (about 10 pt); the page is then snapped to a
     * standard paper or book size of the same shape, and later pages of the same shape get the
     * first page's size, so a scanned book has uniform pages.
     */
    private fun pageScale(w: Int, h: Int, bodySize: Float): Float {
        val longPx = max(w, h).toFloat()
        val guess = (10f / bodySize).coerceIn(7f * 72f / longPx, 14f * 72f / longPx)
        val aspect = h.toFloat() / w
        documentSize?.let { (dw, dh) ->
            // Same shape as the first page: same size; the content is fitted inside it.
            if (kotlin.math.abs(dh / dw - aspect) / aspect < 0.06f) return minOf(dw / w, dh / h)
        }
        val gw = w * guess
        val gh = h * guess
        val portrait = h >= w
        val snapped = STANDARD_SIZES.map { (a, b) -> if (portrait) a to b else b to a }
            .filter { (sw, sh) -> kotlin.math.abs(sh / sw - aspect) / aspect < 0.04f }
            .minByOrNull { (sw, _) -> kotlin.math.abs(sw - gw) }
            ?.takeIf { (sw, _) -> kotlin.math.abs(sw - gw) / sw < 0.25f }
        val scale = snapped?.let { it.first / w } ?: guess
        documentSize = w * scale to h * scale
        return scale
    }

    private class Decision(val p: Placement, var visible: Boolean)

    private class LineFit(val line: OcrLine, val size: Float, val words: List<Pair<Placement, Match>?>, val slant: Float) {
        val inkAmount: Int = words.sumOf { it?.second?.photo ?: 0 }
        /** Photo ink relative to regular-type ink, over the whole line. */
        val weight: Float? = words.sumOf { it?.second?.rendered ?: 0 }.takeIf { it > 0 }?.let { inkAmount.toFloat() / it }
    }

    private fun wordText(line: OcrLine, i: Int) =
        FontLibrary.normalize(line.words[i].text) + if (i < line.words.lastIndex) " " else ""

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
            val bold = when {
                word.text.length >= 3 && wordWeight > BOLD_RATIO + 0.2f -> true
                word.text.length >= 3 && wordWeight < BOLD_RATIO - 0.17f -> false
                else -> lineBold
            }
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
                    // Never accept a style that fits the ink much worse than regular.
                    if (m > fit - 0.08f) { p = alt; fit = m }
                }
            }
            Decision(p, acceptable(word, p, fit, fitter))
        }
    }

    private fun acceptable(word: OcrWord, p: Placement, fit: Float, fitter: WordFitter): Boolean {
        val minFit = if (p.ink.length <= 2) MIN_FIT_SHORT else MIN_FIT
        return word.confidence >= MIN_CONFIDENCE && fit >= minFit && p.scale in 0.6f..1.7f &&
            word.box.height() >= 5 && fitter.onPaper(word.box)
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

    private fun chooseFamily(lines: List<OcrLine>, fitter: WordFitter): FontFamily {
        val all = lines.flatMap { l -> l.words.map { l to it } }.filter { (_, w) -> w.confidence >= 85 && w.text.length >= 3 }
        val sample = if (all.size <= 60) all else all.filterIndexed { i, _ -> i % (all.size / 60 + 1) == 0 }
        if (sample.isEmpty()) return FontFamily.SERIF
        return FontFamily.entries.maxBy { fam ->
            sample.sumOf { (line, w) ->
                val size = fitter.lineSize(line, fonts.face(fam, FontStyle.REGULAR)) ?: return@sumOf 0.0
                val t = FontLibrary.normalize(w.text)
                listOf(FontStyle.REGULAR, FontStyle.BOLD).maxOf { st ->
                    fitter.place(w, t, fonts.face(fam, st), size, line)?.let { fitter.fit(it) } ?: 0f
                }.toDouble()
            }
        }
    }

    /** Cuts a region out of the cleaned colour page; words typeset on top are whited out underneath. */
    private fun cut(colour: Bitmap, r: Rect, typeset: List<Rect>): Graphic? = runCatching {
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
        for (y in 0 until crop.height) {
            crop.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val px = row[x]
                val lo = minOf(Color.red(px), Color.green(px), Color.blue(px))
                if (lo > 215) row[x] = Color.WHITE else if (lo in 60..200) mid++
            }
            crop.setPixels(row, 0, w, 0, y, w, 1)
        }
        Graphic(r, crop, photographic = mid > w.toLong() * crop.height / 4)
    }.getOrNull()

    private fun expand(r: Rect, d: Int) = Rect(r.left - d, r.top - d, r.right + d, r.bottom + d)

    companion object {
        private const val TAG = "PageRebuilder"
        private const val MIN_CONFIDENCE = 55
        /** Share of ink that must agree (within a pixel) between the typeset word and the photo. */
        private const val MIN_FIT = 0.80f
        private const val MIN_FIT_SHORT = 0.86f
        /** Bold type carries about 1.4× the ink of regular type; this is the line between. */
        private const val BOLD_RATIO = 1.25f
        private const val ITALIC_SLANT = 1.03f
        private const val CANDIDATE_MARGIN = 0.03f
        /** At most this many words per page get a second reading (each takes a moment). */
        private const val MAX_REREADS = 160

        /** Paper and common book trim sizes in points (portrait): A4, Letter, A5, B5, Legal, 6×9", 5.5×8.5", 5×8". */
        private val STANDARD_SIZES = listOf(
            595.28f to 841.89f, 612f to 792f, 419.53f to 595.28f, 498.9f to 708.66f, 612f to 1008f,
            432f to 648f, 396f to 612f, 360f to 576f,
        )

        /** Languages whose scripts the bundled fonts can typeset; others keep the photo layout. */
        val SUPPORTED_LANGUAGES = setOf(
            "eng", "fra", "deu", "spa", "ita", "por", "nld", "swe", "nor", "dan", "fin", "pol", "ces", "hun",
            "ron", "tur", "ell", "rus", "ukr", "vie", "ind", "lat",
        )
    }
}
