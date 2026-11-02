package com.narl3yyy.pdfcs.ocr

/**
 * OCR reads letters whose capital and small forms look alike (c o s v w x z, and others in a soft
 * photo) in the wrong case, giving words like "ReqUIrements" or "COMPly". Printed words are
 * lower case, Capitalised or ALL CAPS, apart from a few well-known shapes (PTHs, SnPb, mA, kHz,
 * iPhone, PowerPoint). Anything else is a misreading of the case.
 */
object CaseFix {
    private val upperPlural = Regex("""^\p{Lu}{2,}s$""")
    private val symbols = Regex("""\p{Lu}\p{Ll}?""")
    private val unit = Regex("""^\p{Ll}\p{Lu}\p{Ll}{0,2}$""")
    private val camel = Regex("""^(\p{Lu}\p{Ll}{2,}){2,}$|^[ie](\p{Lu}\p{Ll}{2,})+$""")

    /**
     * True when [text] has a case pattern that print uses. Only plain words are judged: a token
     * with digits or symbols inside ("F=1MHz", "C(pF)", "VRM=5.25V") is a formula or a value,
     * whose letters follow their own rules, and so are units (MHz, mA, kΩ).
     */
    fun plausible(text: String): Boolean {
        val core = text.trim { !it.isLetterOrDigit() }
        if (core.any { !it.isLetter() && it != '-' && it != '/' && it != '\'' && it != '’' }) return true
        if (core in UNITS) return true
        return parts(core).all { part ->
            val letters = part.filter(Char::isLetter)
            letters.length <= 2 ||
                letters.none(Char::isUpperCase) ||
                letters.none(Char::isLowerCase) ||
                (letters[0].isUpperCase() && letters.drop(1).none(Char::isUpperCase)) ||
                upperPlural.matches(letters) || formula(letters) || unit.matches(letters) || camel.matches(letters) || letters in UNITS
        }
    }

    /** Units written with mixed case. */
    private val UNITS = setOf(
        "Hz", "kHz", "MHz", "GHz", "THz", "mA", "µA", "uA", "nA", "pA", "kA", "mV", "µV", "uV", "kV", "MV",
        "mW", "µW", "kW", "MW", "GW", "pF", "nF", "µF", "uF", "mF", "nH", "µH", "uH", "mH", "kΩ", "MΩ", "mΩ",
        "dB", "dBm", "dBV", "dBA", "mL", "dL", "kB", "MB", "GB", "TB", "KB", "kbps", "Mbps", "Gbps", "ppm", "MPa", "kPa", "hPa",
        "kN", "mN", "Nm", "kWh", "mAh", "Ah", "pH", "mOhm", "kOhm", "MOhm", "eV", "keV", "MeV", "mT", "µs", "ns", "ps", "ms",
    )

    /** A chemical formula of two or three element symbols (SnPb, NiAu, SnAgCu). */
    private fun formula(letters: String): Boolean {
        val parts = symbols.findAll(letters).map { it.value }.toList()
        return parts.size in 2..3 && parts.sumOf { it.length } == letters.length && parts.all { it in ELEMENTS }
    }

    private val ELEMENTS = (
        "H He Li Be B C N O F Ne Na Mg Al Si P S Cl Ar K Ca Sc Ti V Cr Mn Fe Co Ni Cu Zn Ga Ge As Se Br Kr " +
            "Rb Sr Y Zr Nb Mo Ru Rh Pd Ag Cd In Sn Sb Te I Xe Cs Ba La Ce Nd Hf Ta W Re Os Ir Pt Au Hg Tl Pb Bi"
        ).split(' ').toSet()

    /**
     * The spellings [text] may really have, most likely first: the case most of its letters were
     * read in (the first letter keeps its capital), then the others. Empty when [text] is fine.
     */
    fun candidates(text: String): List<String> {
        if (plausible(text)) return emptyList()
        val letters = text.filter(Char::isLetter)
        val rest = letters.drop(1)
        val upperShare = rest.count(Char::isUpperCase).toFloat() / rest.length.coerceAtLeast(1)
        val upper = text.uppercase()
        val lower = text.lowercase()
        val capital = mapParts(text) { p ->
            val low = p.lowercase()
            val i = low.indexOfFirst(Char::isLetter)
            if (i < 0) low else low.substring(0, i) + low[i].uppercaseChar() + low.substring(i + 1)
        }
        val firstCapital = letters.firstOrNull()?.isUpperCase() == true
        val ordered = when {
            upperShare > 0.5f -> listOf(upper, if (firstCapital) capital else lower)
            firstCapital -> listOf(capital, upper, lower)
            else -> listOf(lower, capital, upper)
        }
        return (ordered + listOf(lower, capital, upper)).distinct().filter { it != text }
    }

    /** The most likely spelling of [text]: itself when its case is plausible. */
    fun fix(text: String): String = candidates(text).firstOrNull() ?: text

    // Hyphenated and slashed compounds ("Pb-Free", "and/or") are judged part by part.
    private val separators = Regex("[-/]")

    private fun parts(text: String) = text.split(separators)

    private fun mapParts(text: String, f: (String) -> String): String {
        val out = StringBuilder()
        var start = 0
        for (m in separators.findAll(text)) {
            out.append(f(text.substring(start, m.range.first))).append(m.value)
            start = m.range.last + 1
        }
        return out.append(f(text.substring(start))).toString()
    }
}
