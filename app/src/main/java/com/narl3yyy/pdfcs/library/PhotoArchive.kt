package com.narl3yyy.pdfcs.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * Keeps the original photos of every scan in a folder the user picked (Storage Access Framework),
 * one subfolder per scan, so a scan can be converted again later (with a newer PDFCS, or other
 * settings) by importing its photos. The photos are copied as taken, in page order, before the
 * conversion starts, with the page list (crops, turns, looks) beside them.
 */
object PhotoArchive {
    private const val TAG = "PhotoArchive"
    private const val DIR_MIME = DocumentsContract.Document.MIME_TYPE_DIR

    /** Keeps access to a folder picked with OpenDocumentTree across restarts. */
    fun remember(context: Context, tree: Uri) {
        context.contentResolver.takePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    /** Gives up access to a folder no longer used. */
    fun forget(context: Context, tree: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /** True while PDFCS may still write to [tree] (the folder may have been deleted, or access revoked). */
    fun usable(context: Context, tree: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    /** The folder's name as the user knows it ("Scans" or "Download/Scans"). */
    fun label(tree: Uri): String =
        DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifBlank { "Internal storage" }

    /**
     * Copies the photos of [pages] (in [jobDir]) into a new subfolder named [title] of [tree].
     * Returns the number of photos saved; throws when the folder cannot be written.
     */
    fun save(context: Context, tree: Uri, title: String, jobDir: File, pages: List<ScanPage>): Int {
        val resolver = context.contentResolver
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        // The provider renames a duplicate ("Scan (1)"), so an earlier scan is never overwritten.
        val folder = DocumentsContract.createDocument(resolver, root, DIR_MIME, safeName(title))
            ?: error("Could not create a folder for the photos")
        var saved = 0
        val names = HashMap<String, String>()
        pages.forEachIndexed { i, page ->
            val src = File(jobDir, page.source)
            if (!src.exists()) return@forEachIndexed
            // A photo split into two pages (a book spread) is saved once.
            val name = names.getOrPut(page.source) {
                val doc = DocumentsContract.createDocument(resolver, folder, "image/jpeg", "Page %03d".format(i + 1))
                    ?: error("Could not save page ${i + 1}")
                resolver.openOutputStream(doc)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
                saved++
                resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Page %03d.jpg".format(i + 1)
            }
            names[page.source] = name
        }
        // How each page was cropped, turned and cleaned, by file name, for a later re-conversion.
        runCatching {
            val list = org.json.JSONArray()
            for (p in pages) names[p.source]?.let { list.put(p.copy(source = it).toJson()) }
            DocumentsContract.createDocument(resolver, folder, "application/json", "pages.json")?.let { doc ->
                resolver.openOutputStream(doc)!!.use { it.write(list.toString(2).toByteArray()) }
            }
        }.onFailure { Log.w(TAG, "page list not saved", it) }
        Log.i(TAG, "saved $saved photos of '$title'")
        return saved
    }

    private fun safeName(title: String) = title.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "Scan" }
}
