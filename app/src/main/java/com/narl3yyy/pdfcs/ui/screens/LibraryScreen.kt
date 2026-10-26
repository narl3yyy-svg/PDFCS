package com.narl3yyy.pdfcs.ui.screens

import com.narl3yyy.pdfcs.ui.components.rememberLoaded
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.FindInPage
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.narl3yyy.pdfcs.AppGraph
import com.narl3yyy.pdfcs.library.PdfDoc
import com.narl3yyy.pdfcs.reader.PdfPages
import com.narl3yyy.pdfcs.ui.components.EmptyState
import com.narl3yyy.pdfcs.ui.components.PageThumbnail
import com.narl3yyy.pdfcs.ui.formatBytes
import com.narl3yyy.pdfcs.ui.formatDate
import com.narl3yyy.pdfcs.work.OcrWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Jobs whose result the user has already been told about (process lifetime). */
private object SeenJobs {
    val ids = mutableSetOf<UUID>()
    var primed = false
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    incomingPdf: Uri?,
    onIncomingHandled: () -> Unit,
    onScan: () -> Unit,
    onReview: () -> Unit,
    onOpen: (File) -> Unit,
    onOrganize: (File) -> Unit,
    onInfo: (File) -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = AppGraph.documents
    val docs by repo.docs.collectAsState()
    val sessionPages by AppGraph.session.pages.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val selected = remember { mutableStateListOf<PdfDoc>() }
    var filter by remember { mutableStateOf("") }
    var showFilter by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<PdfDoc?>(null) }
    var deleteTargets by remember { mutableStateOf<List<PdfDoc>?>(null) }
    var mergeTargets by remember { mutableStateOf<List<PdfDoc>?>(null) }
    var exportTarget by remember { mutableStateOf<PdfDoc?>(null) }
    var discardScan by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }

    val jobs by remember { WorkManager.getInstance(context).getWorkInfosByTagFlow(OcrWorker.TAG) }
        .collectAsState(initial = null)

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    // Tell the user once when a background PDF job finishes.
    LaunchedEffect(jobs) {
        // null = WorkManager hasn't reported yet; don't prime on that or old jobs get announced again.
        val finished = (jobs ?: return@LaunchedEffect).filter { it.state.isFinished }
        if (!SeenJobs.primed) {
            SeenJobs.ids += finished.map { it.id }
            SeenJobs.primed = true
            return@LaunchedEffect
        }
        for (job in finished) {
            if (!SeenJobs.ids.add(job.id)) continue
            repo.refresh()
            val title = job.outputData.getString(OcrWorker.OUT_TITLE) ?: "PDF"
            when (job.state) {
                WorkInfo.State.SUCCEEDED -> {
                    val path = job.outputData.getString(OcrWorker.KEY_OUTPUT)
                    scope.launch {
                        val r = snackbar.showSnackbar("“$title” is ready and searchable", "Open", duration = SnackbarDuration.Long)
                        if (r == SnackbarResult.ActionPerformed && path != null) onOpen(File(path))
                    }
                }
                WorkInfo.State.FAILED -> scope.launch {
                    snackbar.showSnackbar(job.outputData.getString(OcrWorker.KEY_ERROR) ?: "Creating “$title” failed")
                }
                else -> Unit
            }
        }
    }

    // A PDF shared from another app: copy it in and open it.
    LaunchedEffect(incomingPdf) {
        val uri = incomingPdf ?: return@LaunchedEffect
        onIncomingHandled()
        runCatching { repo.import(uri) }
            .onSuccess(onOpen)
            .onFailure { toast(it.message ?: "Could not open that PDF") }
    }

    val importPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "Importing…"
            var last: File? = null
            uris.forEach { u -> runCatching { repo.import(u) }.onSuccess { last = it }.onFailure { toast(it.message ?: "Import failed") } }
            busy = null
            if (uris.size == 1) last?.let(onOpen)
        }
    }
    val ocrPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "Reading PDF…"
            val result = runCatching {
                val tmp = File(context.cacheDir, "ocr_import.pdf")
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                }
                PdfPages.importIntoSession(tmp, AppGraph.session) { d, t -> busy = "Preparing page $d of $t…" }
                tmp.delete()
            }
            busy = null
            result.onSuccess { onReview() }.onFailure { toast(it.message ?: "Could not read that PDF") }
        }
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "Adding images…"
            importImages(context, uris) { d, t -> busy = "Adding image $d of $t…" }
            busy = null
            onReview()
        }
    }
    val saveToDevice = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val doc = exportTarget
        exportTarget = null
        if (uri == null || doc == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { repo.exportTo(doc, uri) }
                .onSuccess { snackbar.showSnackbar("Saved “${doc.title}.pdf”") }
                .onFailure { toast(it.message ?: "Could not save") }
        }
    }

    BackHandler(enabled = selected.isNotEmpty()) { selected.clear() }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val visibleDocs = remember(docs, filter) {
        if (filter.isBlank()) docs else docs.filter { it.title.contains(filter.trim(), ignoreCase = true) }
    }
    val activeJobs = jobs.orEmpty().filter { !it.state.isFinished }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selected.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selected.size} selected") },
                    navigationIcon = { IconButton(onClick = { selected.clear() }) { Icon(Icons.Rounded.Close, "Clear selection") } },
                    actions = {
                        if (selected.size >= 2) {
                            IconButton(onClick = { mergeTargets = selected.toList() }) { Icon(Icons.AutoMirrored.Rounded.CallMerge, "Merge") }
                        }
                        IconButton(onClick = { share(context, selected.toList()) }) { Icon(Icons.Rounded.Share, "Share") }
                        IconButton(onClick = { deleteTargets = selected.toList() }) { Icon(Icons.Rounded.Delete, "Delete") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                )
            } else {
                LargeTopAppBar(
                    title = {
                        Column {
                            Text("PDFCS")
                            if (scrollBehavior.state.collapsedFraction < 0.5f) {
                                Text(
                                    "Scan, read, edit and convert PDFs",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        if (docs.isNotEmpty()) {
                            IconButton(onClick = { showFilter = !showFilter; if (!showFilter) filter = "" }) {
                                Icon(Icons.Rounded.Search, "Find a document")
                            }
                        }
                        Box {
                            IconButton(onClick = { addMenu = true }) { Icon(Icons.AutoMirrored.Rounded.NoteAdd, "Add") }
                            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Import PDF") },
                                    leadingIcon = { Icon(Icons.Rounded.FileOpen, null) },
                                    onClick = { addMenu = false; importPdf.launch(arrayOf("application/pdf")) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Images to searchable PDF") },
                                    leadingIcon = { Icon(Icons.Rounded.AddPhotoAlternate, null) },
                                    onClick = {
                                        addMenu = false
                                        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Make a PDF searchable (OCR)") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.TextSnippet, null) },
                                    onClick = { addMenu = false; ocrPdf.launch(arrayOf("application/pdf")) },
                                )
                            }
                        }
                        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        floatingActionButton = {
            if (selected.isEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onScan,
                    icon = { Icon(Icons.Rounded.DocumentScanner, null) },
                    text = { Text(if (sessionPages.isEmpty()) "Scan book" else "Keep scanning") },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showFilter && selected.isEmpty()) {
                item("filter") {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        placeholder = { Text("Filter by title") },
                        shape = RoundedCornerShape(28.dp),
                    )
                }
            }
            items(activeJobs, key = { it.id }) { job ->
                JobCard(job, onCancel = { WorkManager.getInstance(context).cancelWorkById(job.id) })
            }
            if (sessionPages.isNotEmpty()) {
                item("session") {
                    ScanInProgressCard(
                        pages = sessionPages.size,
                        onContinue = onReview,
                        onDiscard = { discardScan = true },
                    )
                }
            }
            if (docs.isEmpty() && sessionPages.isEmpty() && activeJobs.isEmpty()) {
                item("empty") {
                    EmptyState(
                        icon = Icons.Rounded.DocumentScanner,
                        title = "Scan your first book",
                        body = "Photograph each page with your camera. PDFCS straightens and cleans the pages, reads them with " +
                            "on-device OCR and rebuilds them as a real digital PDF you can search, highlight and edit.",
                        action = "Start scanning",
                        onAction = onScan,
                        modifier = Modifier.padding(top = 32.dp),
                    )
                }
            } else if (visibleDocs.isNotEmpty()) {
                item("header") {
                    Text(
                        "Documents",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
            }
            items(visibleDocs, key = { it.file.path }) { doc ->
                val isSelected = doc in selected
                DocumentRow(
                    doc = doc,
                    selected = isSelected,
                    selectionMode = selected.isNotEmpty(),
                    onClick = {
                        if (selected.isNotEmpty()) {
                            if (isSelected) selected.remove(doc) else selected.add(doc)
                        } else onOpen(doc.file)
                    },
                    onLongClick = { if (isSelected) selected.remove(doc) else selected.add(doc) },
                    onShare = { share(context, listOf(doc)) },
                    onSave = { exportTarget = doc; saveToDevice.launch("${doc.title}.pdf") },
                    onRename = { renameTarget = doc },
                    onOrganize = { onOrganize(doc.file) },
                    onInfo = { onInfo(doc.file) },
                    onOcr = {
                        scope.launch {
                            busy = "Preparing pages…"
                            runCatching {
                                PdfPages.importIntoSession(doc.file, AppGraph.session) { d, t -> busy = "Preparing page $d of $t…" }
                            }.onSuccess { onReview() }.onFailure { toast(it.message ?: "Could not read that PDF") }
                            busy = null
                        }
                    },
                    onDelete = { deleteTargets = listOf(doc) },
                )
            }
            item("about") {
                TextButton(onClick = onAbout, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Info, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Free software · GPL-3.0")
                }
            }
        }
    }

    busy?.let { BusyDialog(it) }

    renameTarget?.let { doc ->
        TextInputDialog(
            title = "Rename",
            initial = doc.title,
            confirm = "Rename",
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                scope.launch { if (repo.rename(doc, name) == null) toast("Could not rename") }
            },
        )
    }
    mergeTargets?.let { list ->
        TextInputDialog(
            title = "Merge ${list.size} PDFs",
            message = "Pages are joined in the order you selected the documents.",
            initial = list.first().title + " (merged)",
            confirm = "Merge",
            onDismiss = { mergeTargets = null },
            onConfirm = { name ->
                mergeTargets = null
                selected.clear()
                scope.launch {
                    busy = "Merging…"
                    runCatching { repo.merge(list, name) }
                        .onSuccess { snackbar.showSnackbar("Created “${it.nameWithoutExtension}”") }
                        .onFailure { toast(it.message ?: "Merge failed") }
                    busy = null
                }
            },
        )
    }
    deleteTargets?.let { list ->
        AlertDialog(
            onDismissRequest = { deleteTargets = null },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text(if (list.size == 1) "Delete “${list.first().title}”?" else "Delete ${list.size} documents?") },
            text = { Text("This can’t be undone. Save a copy to your device first if you might need it.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTargets = null
                    selected.clear()
                    scope.launch { repo.delete(list) }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTargets = null }) { Text("Cancel") } },
        )
    }
    if (discardScan) {
        AlertDialog(
            onDismissRequest = { discardScan = false },
            title = { Text("Discard this scan?") },
            text = { Text("All ${sessionPages.size} captured pages will be deleted.") },
            confirmButton = {
                TextButton(onClick = { discardScan = false; AppGraph.session.clear() }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { discardScan = false }) { Text("Keep") } },
        )
    }
}

suspend fun importImages(context: Context, uris: List<Uri>, onProgress: (Int, Int) -> Unit) =
    withContext(Dispatchers.IO) {
        val session = AppGraph.session
        val look = AppGraph.settings.value.defaultLook
        uris.forEachIndexed { i, uri ->
            runCatching {
                val out = session.newImageFile()
                context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
                session.addPage(out, look, findEdges = AppGraph.settings.value.autoCrop)
            }
            withContext(Dispatchers.Main) { onProgress(i + 1, uris.size) }
        }
    }

fun share(context: Context, docs: List<PdfDoc>) {
    if (docs.isEmpty()) return
    val authority = "${context.packageName}.files"
    val uris = docs.map { FileProvider.getUriForFile(context, authority, it.file) }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    intent.type = "application/pdf"
    intent.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share PDF"))
}

@Composable
private fun JobCard(job: WorkInfo, onCancel: () -> Unit) {
    val done = job.progress.getInt(OcrWorker.PROGRESS_DONE, 0)
    val total = job.progress.getInt(OcrWorker.PROGRESS_TOTAL, 0)
    val title = job.progress.getString(OcrWorker.KEY_TITLE) ?: "Searchable PDF"
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (job.state == WorkInfo.State.ENQUEUED) "Waiting…" else "Recognising text",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (total > 0) "$title · page ${minOf(done + 1, total)} of $total" else title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            Spacer(Modifier.height(10.dp))
            if (total > 0) {
                LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                "You can leave the app — this keeps running in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ScanInProgressCard(pages: Int, onContinue: () -> Unit, onDiscard: () -> Unit) {
    val session = AppGraph.session
    val list by session.pages.collectAsState()
    ElevatedCard(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 56.dp, height = 72.dp)) {
                list.firstOrNull()?.let { PageThumbnail(session.dir, it, Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Scan in progress", style = MaterialTheme.typography.titleMedium)
                Text(
                    "$pages page${if (pages == 1) "" else "s"} captured · not yet a PDF",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onContinue) {
                        Text("Review & finish")
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
                    }
                    TextButton(onClick = onDiscard) { Text("Discard") }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentRow(
    doc: PdfDoc,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onRename: () -> Unit,
    onOrganize: () -> Unit,
    onInfo: () -> Unit,
    onOcr: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val thumb by rememberLoaded<Bitmap>(null, doc.file.path, doc.modified) { AppGraph.documents.thumbnail(doc.file) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 56.dp, height = 74.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val b = thumb
                if (b != null) {
                    Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.Rounded.PictureAsPdf, null, tint = MaterialTheme.colorScheme.primary)
                }
                if (selected) {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))),
                        ),
                    )
                    Icon(
                        Icons.Rounded.CheckCircle, null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(20.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    doc.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${doc.pages} page${if (doc.pages == 1) "" else "s"} · ${formatBytes(doc.sizeBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatDate(doc.modified),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(!selectionMode) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Rounded.Share, null) },
                            onClick = { menu = false; onShare() })
                        DropdownMenuItem(text = { Text("Save to device") }, leadingIcon = { Icon(Icons.Rounded.Download, null) },
                            onClick = { menu = false; onSave() })
                        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, null) },
                            onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Organize pages") }, leadingIcon = { Icon(Icons.Rounded.ViewModule, null) },
                            onClick = { menu = false; onOrganize() })
                        DropdownMenuItem(text = { Text("Analyze") }, leadingIcon = { Icon(Icons.Rounded.Analytics, null) },
                            onClick = { menu = false; onInfo() })
                        DropdownMenuItem(text = { Text("Make searchable (OCR)") }, leadingIcon = { Icon(Icons.Rounded.FindInPage, null) },
                            onClick = { menu = false; onOcr() })
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BusyDialog(message: String) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Spacer(Modifier.width(20.dp))
                Text(message, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
        },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    message: String? = null,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                message?.let { Text(it, Modifier.padding(bottom = 12.dp)) }
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

