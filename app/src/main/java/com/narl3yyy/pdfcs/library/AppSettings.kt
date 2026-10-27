package com.narl3yyy.pdfcs.library

import android.content.Context
import com.narl3yyy.pdfcs.camera.Look
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a finished scan looks like. */
enum class OutputStyle(val label: String, val hint: String) {
    DIGITAL(
        "Digital text",
        "Pages rebuilt with real, sharp text you can select, highlight and search, like a PDF made on a computer",
    ),
    PHOTO("Original photos", "Keeps each photo exactly as taken, with an invisible searchable text layer"),
}

/** The typeface family used to rebuild pages. */
enum class FontChoice(val label: String) { AUTO("Match the page"), SERIF("Serif"), SANS("Sans-serif") }

/** How page images are stored in "Original photos" PDFs. OCR always reads the full-resolution page. */
enum class PdfQuality(val label: String, val maxSide: Int, val jpegQuality: Int, val hint: String) {
    HIGH("High", 3000, 88, "Sharpest images, larger file"),
    BALANCED("Balanced", 2200, 80, "Recommended for books"),
    COMPACT("Compact", 1600, 70, "Smallest file"),
}

data class Settings(
    val ocrLanguages: List<String> = listOf("eng"),
    val defaultLook: Look = Look.COLOUR,
    val quality: PdfQuality = PdfQuality.BALANCED,
    val outputStyle: OutputStyle = OutputStyle.DIGITAL,
    val fontChoice: FontChoice = FontChoice.AUTO,
    val volumeShutter: Boolean = true,
    val autoCaptureSeconds: Int = 4,
    val shutterSound: Boolean = true,
    val autoCrop: Boolean = true,
    /** Imported scans and photos become searchable PDFs by themselves (no review step). */
    val autoOcrImport: Boolean = true,
)

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val value: Settings get() = _state.value

    private fun read(): Settings {
        val d = Settings()
        return Settings(
            ocrLanguages = prefs.getString("ocrLanguages", null)
                ?.split('+')?.filter { it.isNotBlank() }?.ifEmpty { null } ?: d.ocrLanguages,
            defaultLook = enumOr(prefs.getString("defaultLook", null), d.defaultLook),
            quality = enumOr(prefs.getString("quality", null), d.quality),
            outputStyle = enumOr(prefs.getString("outputStyle", null), d.outputStyle),
            fontChoice = enumOr(prefs.getString("fontChoice", null), d.fontChoice),
            volumeShutter = prefs.getBoolean("volumeShutter", d.volumeShutter),
            autoCaptureSeconds = prefs.getInt("autoCaptureSeconds", d.autoCaptureSeconds).coerceIn(2, 10),
            shutterSound = prefs.getBoolean("shutterSound", d.shutterSound),
            autoCrop = prefs.getBoolean("autoCrop", d.autoCrop),
            autoOcrImport = prefs.getBoolean("autoOcrImport", d.autoOcrImport),
        )
    }

    fun update(block: (Settings) -> Settings) {
        val s = block(_state.value)
        prefs.edit()
            .putString("ocrLanguages", s.ocrLanguages.joinToString("+"))
            .putString("defaultLook", s.defaultLook.name)
            .putString("quality", s.quality.name)
            .putString("outputStyle", s.outputStyle.name)
            .putString("fontChoice", s.fontChoice.name)
            .putBoolean("volumeShutter", s.volumeShutter)
            .putInt("autoCaptureSeconds", s.autoCaptureSeconds)
            .putBoolean("shutterSound", s.shutterSound)
            .putBoolean("autoCrop", s.autoCrop)
            .putBoolean("autoOcrImport", s.autoOcrImport)
            .apply()
        _state.value = s
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
