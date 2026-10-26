package com.narl3yyy.pdfcs.library

import android.content.Context
import com.narl3yyy.pdfcs.camera.Look
import com.narl3yyy.pdfcs.camera.PageImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * One captured page. Edits are non-destructive: the original photo is kept and
 * crop, rotation and filter are applied whenever the page is rendered.
 *
 * [quad] holds 8 normalised coordinates (top-left, top-right, bottom-right,
 * bottom-left) in the EXIF-corrected source image, or null for the whole photo.
 */
data class ScanPage(
    val id: String = UUID.randomUUID().toString(),
    val source: String,
    val quad: List<Float>? = null,
    val rotation: Int = 0,
    val look: Look = Look.COLOUR,
) {
    /** Changes whenever the rendered output would change; used as a cache key. */
    val renderKey: String get() = "$source|$quad|$rotation|$look"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("source", source)
        quad?.let { q -> put("quad", JSONArray().apply { q.forEach { put(it.toDouble()) } }) }
        put("rotation", rotation)
        put("look", look.name)
    }

    companion object {
        val FULL_QUAD = listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

        fun fromJson(o: JSONObject): ScanPage = ScanPage(
            id = o.getString("id"),
            source = o.getString("source"),
            quad = o.optJSONArray("quad")?.let { a ->
                if (a.length() == 8) List(8) { a.getDouble(it).toFloat() } else null
            },
            rotation = o.optInt("rotation", 0),
            look = Look.entries.firstOrNull { it.name == o.optString("look") } ?: Look.COLOUR,
        )
    }
}

/**
 * The scan in progress. It lives on disk (photos + pages.json) so a long book
 * scan survives the app being closed, and can be continued later.
 */
class ScanSession(context: Context) {
    private val root = File(context.filesDir, "scan")
    val dir: File = File(root, "current")
    private val _pages = MutableStateFlow(readPages(dir))
    val pages: StateFlow<List<ScanPage>> = _pages.asStateFlow()

    @Synchronized
    fun newImageFile(): File {
        dir.mkdirs()
        return File(dir, "img_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")
    }

    fun fileOf(page: ScanPage): File = File(dir, page.source)

    /** Adds a page and returns its id. With [findEdges], the page's corners are found in the background. */
    @Synchronized
    fun addPage(image: File, look: Look, findEdges: Boolean = false): String {
        val page = ScanPage(source = image.name, look = look)
        mutate { it + page }
        if (findEdges) autoCrop(page.id)
        return page.id
    }

    /** One page at a time, in capture order, so pages never race each other. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val cropScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    /** Finds the page's edges and straightens it, unless the user has cropped it in the meantime. */
    private fun autoCrop(id: String) {
        cropScope.launch {
            val page = pages.value.firstOrNull { it.id == id } ?: return@launch
            val quad = PageImage.detectPage(fileOf(page)) ?: return@launch
            synchronized(this@ScanSession) {
                val now = pages.value.firstOrNull { it.id == id } ?: return@launch
                if (now.quad == null && now.source == page.source) update(now.copy(quad = quad))
            }
        }
    }

    @Synchronized
    fun update(page: ScanPage) = mutate { list -> list.map { if (it.id == page.id) page else it } }

    @Synchronized
    fun replace(id: String, with: List<ScanPage>) = mutate { list ->
        list.flatMap { if (it.id == id) with else listOf(it) }
    }

    @Synchronized
    fun remove(id: String) {
        val removed = _pages.value.firstOrNull { it.id == id } ?: return
        mutate { list -> list.filterNot { it.id == id } }
        if (_pages.value.none { it.source == removed.source }) File(dir, removed.source).delete()
    }

    @Synchronized
    fun move(id: String, delta: Int) = mutate { list ->
        val i = list.indexOfFirst { it.id == id }
        val j = (i + delta).coerceIn(0, list.lastIndex)
        if (i < 0 || i == j) list else list.toMutableList().apply { add(j, removeAt(i)) }
    }

    @Synchronized
    fun setAll(transform: (List<ScanPage>) -> List<ScanPage>) = mutate(transform)

    @Synchronized
    fun clear() {
        dir.deleteRecursively()
        _pages.value = emptyList()
    }

    /** Moves the current scan into [target] (for a background OCR job) and starts a new, empty one. */
    @Synchronized
    fun detachInto(target: File): Boolean {
        target.parentFile?.mkdirs()
        val ok = dir.renameTo(target)
        if (ok) _pages.value = emptyList()
        return ok
    }

    /** Puts the pages of a failed job back in front of anything scanned since, so no photo is lost. */
    @Synchronized
    fun restoreFrom(jobDir: File): Boolean {
        val restored = readPages(jobDir)
        if (restored.isEmpty()) {
            jobDir.deleteRecursively()
            return false
        }
        dir.mkdirs()
        restored.map { it.source }.distinct().forEach { File(jobDir, it).renameTo(File(dir, it)) }
        val moved = restored.filter { File(dir, it.source).exists() }
        mutate { moved + it }
        jobDir.deleteRecursively()
        return moved.isNotEmpty()
    }

    private fun mutate(block: (List<ScanPage>) -> List<ScanPage>) {
        val next = block(_pages.value)
        _pages.value = next
        writePages(dir, next)
    }

    companion object {
        fun readPages(dir: File): List<ScanPage> {
            val f = File(dir, "pages.json")
            if (!f.exists()) return emptyList()
            return runCatching {
                val arr = JSONArray(f.readText())
                List(arr.length()) { ScanPage.fromJson(arr.getJSONObject(it)) }
                    .filter { File(dir, it.source).exists() }
            }.getOrDefault(emptyList())
        }

        fun writePages(dir: File, pages: List<ScanPage>) {
            dir.mkdirs()
            val tmp = File(dir, "pages.json.tmp")
            tmp.writeText(JSONArray().apply { pages.forEach { put(it.toJson()) } }.toString())
            tmp.renameTo(File(dir, "pages.json"))
        }
    }
}
