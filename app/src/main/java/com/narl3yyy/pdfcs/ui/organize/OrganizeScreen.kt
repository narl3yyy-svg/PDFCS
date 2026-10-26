package com.narl3yyy.pdfcs.ui.organize

import com.narl3yyy.pdfcs.ui.components.rememberLoaded
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.automirrored.rounded.RotateLeft
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.narl3yyy.pdfcs.AppGraph
import com.narl3yyy.pdfcs.core.PageRanges
import com.narl3yyy.pdfcs.organize.PageOps
import com.narl3yyy.pdfcs.reader.PdfPages
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.launch
import java.io.File

/** Organize Pages (after PdfCraft's "pages like cards on a table"): select, then act. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizeScreen(path: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val file = remember(path) { File(path) }
    val session = remember(path) { AppGraph.sessionFor(file) }
    val version by session.version.collectAsState()
    val history by session.history.collectAsState()
    val pages = remember(version) { runCatching { PdfPages(file) }.getOrNull() }
    DisposableEffect(pages) { onDispose { pages?.close() } }
    val count = pages?.pageCount ?: 0
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun act(label: String, done: String? = null, keep: (Set<Int>) -> Set<Int> = { emptySet() }, change: (PDDocument) -> Unit) {
        scope.launch {
            busy = true
            runCatching { session.edit(label, change) }
                .onSuccess { selected = keep(selected); done?.let { snackbar.showSnackbar(it) } }
                .onFailure { Toast.makeText(context, it.message ?: "$label failed", Toast.LENGTH_LONG).show() }
            busy = false
        }
    }
    val sel = selected.sorted()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Organize pages")
                        Text(
                            if (selected.isEmpty()) "$count pages · tap pages to select" else "${selected.size} selected: ${PageRanges.format(selected)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Done") } },
                actions = {
                    IconButton(onClick = { scope.launch { session.undo() } }, enabled = history.undoLabel != null) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, "Undo")
                    }
                    IconButton(onClick = { scope.launch { session.redo() } }, enabled = history.redoLabel != null) {
                        Icon(Icons.AutoMirrored.Rounded.Redo, "Redo")
                    }
                    IconButton(onClick = { selected = if (selected.size == count) emptySet() else (0 until count).toSet() }) {
                        Icon(Icons.Rounded.SelectAll, "Select all")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding()) {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        val any = selected.isNotEmpty() && !busy
                        Action(Icons.AutoMirrored.Rounded.RotateLeft, "Left", any) { act("Rotate", keep = { it }) { PageOps.rotate(it, sel, -90) } }
                        Action(Icons.AutoMirrored.Rounded.RotateRight, "Right", any) { act("Rotate", keep = { it }) { PageOps.rotate(it, sel, 90) } }
                        Action(Icons.Rounded.KeyboardDoubleArrowLeft, "Earlier", any && sel.first() > 0) {
                            act("Move pages", keep = { s -> s.map { it - 1 }.toSet() }) { PageOps.move(it, sel, sel.first() - 1) }
                        }
                        Action(Icons.Rounded.KeyboardDoubleArrowRight, "Later", any && sel.last() < count - 1) {
                            act("Move pages", keep = { s -> s.map { it + 1 }.toSet() }) { PageOps.move(it, sel, sel.first() + 1 + sel.size) }
                        }
                        Action(Icons.Rounded.ContentCopy, "Duplicate", any && selected.size == 1) {
                            act("Duplicate page", "Page duplicated") { PageOps.duplicate(it, sel.first()) }
                        }
                        Action(Icons.AutoMirrored.Rounded.NoteAdd, "Blank page", !busy) {
                            val at = (sel.lastOrNull() ?: (count - 1)) + 1
                            act("Insert blank page", "Blank page inserted") { PageOps.insertBlank(it, at) }
                        }
                        Action(Icons.Rounded.Upload, "Extract", any) {
                            scope.launch {
                                busy = true
                                runCatching {
                                    val target = AppGraph.documents.uniqueFile("${file.nameWithoutExtension} – pages ${PageRanges.format(sel)}")
                                    session.read { PageOps.extract(it, sel, target) }
                                    AppGraph.documents.refresh()
                                    target
                                }.onSuccess { snackbar.showSnackbar("Saved “${it.nameWithoutExtension}” in your library") }
                                    .onFailure { Toast.makeText(context, it.message ?: "Extract failed", Toast.LENGTH_LONG).show() }
                                busy = false
                            }
                        }
                        Action(Icons.Rounded.SwapVert, "Reverse", !busy && count > 1) { act("Reverse pages", "Page order reversed") { PageOps.reverse(it) } }
                        Action(Icons.Rounded.Delete, "Delete", any && selected.size < count) { confirmDelete = true }
                    }
                }
            }
        },
    ) { padding ->
        val p = pages
        if (p == null) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text("These pages can’t be shown. If the document is password-protected, remove its password first (All tools ▸ Remove password).")
            }
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, padding.calculateBottomPadding() + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items((0 until count).toList(), key = { "$version-$it" }) { i ->
                val isSel = i in selected
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                    selected = if (isSel) selected - i else selected + i
                }) {
                    Thumb(p, i, isSel)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.background(if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${selected.size} page${if (selected.size == 1) "" else "s"}?") },
            text = { Text("You can undo this.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; act("Delete pages", "Pages deleted") { PageOps.delete(it, sel) } }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Thumb(pages: PdfPages, i: Int, selected: Boolean) {
    val bmp by rememberLoaded(pages.cached(i, 320), pages, i) { pages.render(i, 320) }
    val size = pages.sizes.getOrNull(i)
    Box(
        Modifier.fillMaxWidth().aspectRatio(size?.let { it.width / it.height } ?: 0.72f)
            .background(Color.White, RoundedCornerShape(6.dp))
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxSize().padding(2.dp), contentScale = ContentScale.Fit)
        else CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).background(Color.White, CircleShape))
    }
}

@Composable
private fun Action(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 4.dp)) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, label) }
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    }
}
