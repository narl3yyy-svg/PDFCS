package com.narl3yyy.pdfcs.clearscan

import android.content.Context
import android.graphics.Typeface
import com.tom_roush.fontbox.ttf.TTFParser
import com.tom_roush.fontbox.ttf.TrueTypeFont
import java.io.File
import java.text.Normalizer

enum class FontFamily(val file: String) { SERIF("LiberationSerif"), SANS("LiberationSans") }

enum class FontStyle(val file: String, val bold: Boolean, val italic: Boolean) {
    REGULAR("Regular", false, false),
    BOLD("Bold", true, false),
    ITALIC("Italic", false, true),
    BOLD_ITALIC("BoldItalic", true, true),
}

/** Ink extent of a string set in one font, in em units (y up, origin at the start of the baseline). */
class Shape(val left: Float, val right: Float, val bottom: Float, val top: Float) {
    val width get() = right - left
    val height get() = top - bottom
}

/** One font file: metrics for fitting words, a Typeface for comparing shapes, the file for embedding. */
class FontFace(val family: FontFamily, val style: FontStyle, val file: File) {
    private val ttf: TrueTypeFont = TTFParser().parse(file)
    private val upem = ttf.unitsPerEm.toFloat()
    private val cmap = ttf.getUnicodeCmapLookup(false)
    private val glyphs = ttf.glyph
    val typeface: Typeface = Typeface.createFromFile(file)
    private val boxes = HashMap<Int, FloatArray?>()

    fun supports(cp: Int): Boolean = cp == ' '.code || cmap.getGlyphId(cp) != 0

    fun supportsAll(text: String): Boolean = text.codePoints().allMatch { supports(it) }

    /** Ink box of [text] in em, or null if a character is missing or nothing has ink. */
    fun shape(text: String): Shape? {
        var x = 0f
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = Float.MAX_VALUE
        var top = -Float.MAX_VALUE
        val cps = text.codePoints().toArray()
        for (cp in cps) {
            val gid = cmap.getGlyphId(cp)
            if (gid == 0 && cp != ' '.code) return null
            val b = synchronized(boxes) {
                boxes.getOrPut(gid) {
                    glyphs.getGlyph(gid)?.boundingBox?.let { bb ->
                        if (bb.width <= 0f || bb.height <= 0f) null
                        else floatArrayOf(bb.lowerLeftX / upem, bb.lowerLeftY / upem, bb.upperRightX / upem, bb.upperRightY / upem)
                    }
                }
            }
            if (b != null) {
                left = minOf(left, x + b[0]); right = maxOf(right, x + b[2])
                bottom = minOf(bottom, b[1]); top = maxOf(top, b[3])
            }
            x += ttf.getAdvanceWidth(gid) / upem
        }
        if (left == Float.MAX_VALUE) return null
        return Shape(left, right, bottom, top)
    }
}

/** The bundled Liberation fonts (SIL OFL 1.1), metric-compatible with Times and Arial. */
class FontLibrary private constructor(context: Context) {
    val faces: Map<FontFamily, Map<FontStyle, FontFace>>

    init {
        val dir = File(context.filesDir, "fonts").apply { mkdirs() }
        faces = FontFamily.entries.associateWith { fam ->
            FontStyle.entries.associateWith { st ->
                val name = "${fam.file}-${st.file}.ttf"
                val f = File(dir, name)
                if (!f.exists() || f.length() == 0L) {
                    val tmp = File(dir, "$name.tmp")
                    context.assets.open("fonts/$name").use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    tmp.renameTo(f)
                }
                FontFace(fam, st, f)
            }
        }
    }

    fun face(family: FontFamily, style: FontStyle): FontFace = faces.getValue(family).getValue(style)

    companion object {
        @Volatile private var instance: FontLibrary? = null
        fun get(context: Context): FontLibrary =
            instance ?: synchronized(this) { instance ?: FontLibrary(context.applicationContext).also { instance = it } }

        /** OCR sometimes emits ligatures and compatibility forms; fold them to plain letters. */
        fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
    }
}
