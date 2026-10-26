package com.narl3yyy.pdfcs.engine

import com.narl3yyy.pdfcs.core.AtomicFile
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Thrown when a document needs a password (or the one given is wrong). */
class PasswordRequired(val wrong: Boolean) : Exception(if (wrong) "Wrong password" else "This document is password-protected")

/**
 * An open document being edited (after PdfCraft's engine session): every change is one undoable
 * step. A step loads the current file, applies the change, and saves atomically over it, so the
 * file on disk is always a complete, valid PDF; the previous version is kept for Undo.
 */
class DocumentSession(val file: File, private val cacheDir: File, var password: String? = null) {
    private val lock = Mutex()
    private val undoDir = File(cacheDir, "undo/${file.name.hashCode()}_${System.nanoTime()}").apply { mkdirs() }
    private val undo = ArrayDeque<Step>()
    private val redo = ArrayDeque<Step>()
    private var counter = 0

    private class Step(val label: String, val snapshot: File)

    /** Bumps on every change, so viewers know to reload. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _history = MutableStateFlow(History(null, null))
    val history: StateFlow<History> = _history.asStateFlow()

    data class History(val undoLabel: String?, val redoLabel: String?)

    /** Opens the current file read-only (for inspection, export, text). */
    suspend fun <T> read(block: (PDDocument) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock { open().use(block) }
    }

    /**
     * Applies [change] as one step called [label]. The change edits the loaded document; it is
     * saved only if the change returns without throwing, so a failed edit leaves the file as it was.
     */
    suspend fun <T> edit(label: String, change: (PDDocument) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            val snapshot = File(undoDir, "${counter++}.pdf")
            file.copyTo(snapshot, overwrite = true)
            val result = try {
                open().use { doc ->
                    val r = change(doc)
                    // A protected document stays protected with the same password.
                    AtomicFile.write(file) { tmp -> doc.save(tmp) }
                    r
                }
            } catch (t: Throwable) {
                snapshot.delete()
                throw t
            }
            undo.addLast(Step(label, snapshot))
            while (undo.size > MAX_UNDO) undo.removeFirst().snapshot.delete()
            redo.forEach { it.snapshot.delete() }
            redo.clear()
            changed()
            result
        }
    }

    /** Replaces the file with [newFile]'s bytes as one step (for whole-file tools like Optimize). */
    suspend fun replaceWith(label: String, newFile: File) = withContext(Dispatchers.IO) {
        lock.withLock {
            val snapshot = File(undoDir, "${counter++}.pdf")
            file.copyTo(snapshot, overwrite = true)
            AtomicFile.write(file) { tmp -> newFile.copyTo(tmp, overwrite = true) }
            undo.addLast(Step(label, snapshot))
            redo.forEach { it.snapshot.delete() }
            redo.clear()
            changed()
        }
    }

    suspend fun undo(): Boolean = swap(undo, redo)
    suspend fun redo(): Boolean = swap(redo, undo)

    private suspend fun swap(from: ArrayDeque<Step>, to: ArrayDeque<Step>): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val step = from.removeLastOrNull() ?: return@withLock false
            val current = File(undoDir, "${counter++}.pdf")
            file.copyTo(current, overwrite = true)
            AtomicFile.write(file) { tmp -> step.snapshot.copyTo(tmp, overwrite = true) }
            step.snapshot.delete()
            to.addLast(Step(step.label, current))
            changed()
            true
        }
    }

    private fun changed() {
        _history.value = History(undo.lastOrNull()?.label, redo.lastOrNull()?.label)
        _version.value++
    }

    private fun open(): PDDocument = try {
        PDDocument.load(file, password ?: "", MemoryUsageSetting.setupTempFileOnly())
    } catch (e: InvalidPasswordException) {
        throw PasswordRequired(wrong = password != null)
    }

    /** True when the file can be opened without a password. */
    suspend fun needsPassword(): Boolean = withContext(Dispatchers.IO) {
        runCatching { PDDocument.load(file, "", MemoryUsageSetting.setupTempFileOnly()).close() }
            .exceptionOrNull() is InvalidPasswordException
    }

    fun close() {
        undoDir.deleteRecursively()
    }

    companion object {
        const val MAX_UNDO = 20
    }
}
