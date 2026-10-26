package com.narl3yyy.pdfcs.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.narl3yyy.pdfcs.AppGraph
import com.narl3yyy.pdfcs.camera.Look
import com.narl3yyy.pdfcs.library.OutputStyle
import com.narl3yyy.pdfcs.library.PdfQuality
import com.narl3yyy.pdfcs.clearscan.PageRebuilder
import com.narl3yyy.pdfcs.library.FontChoice
import com.narl3yyy.pdfcs.camera.PageImage
import com.narl3yyy.pdfcs.ocr.OcrLanguages
import com.narl3yyy.pdfcs.ui.components.EmptyState
import com.narl3yyy.pdfcs.ui.components.PageThumbnail
import com.narl3yyy.pdfcs.ui.defaultScanTitle
import com.narl3yyy.pdfcs.work.OcrWorker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    onAddPages: () -> Unit,
    onEditPage: (String) -> Unit,
    onExported: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = AppGraph.session
    val pages by session.pages.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "Adding images…"
            importImages(context, uris) { d, t -> busy = "Adding image $d of $t…" }
            busy = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Review pages")
                        Text(
                            "${pages.size} page${if (pages.size == 1) "" else "s"} · tap a page to crop or adjust",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onAddPages) { Icon(Icons.Rounded.AddAPhoto, "Scan more pages") }
                    IconButton(onClick = {
                        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Icon(Icons.Rounded.AddPhotoAlternate, "Add images") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            Look.entries.forEach { f ->
                                DropdownMenuItem(
                                    text = { Text("All pages: ${f.label}") },
                                    leadingIcon = { Icon(Icons.Rounded.AutoFixHigh, null) },
                                    onClick = {
                                        menu = false
                                        session.setAll { list -> list.map { it.copy(look = f) } }
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Split all two-page spreads") },
                                leadingIcon = { Icon(Icons.Rounded.ContentCut, null) },
                                onClick = {
                                    menu = false
                                    session.setAll { list ->
                                        list.flatMap { p ->
                                            val (l, r) = PageImage.splitQuad(p.quad)
                                            listOf(p.copy(id = java.util.UUID.randomUUID().toString(), quad = l),
                                                p.copy(id = java.util.UUID.randomUUID().toString(), quad = r))
                                        }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Reverse page order") },
                                leadingIcon = { Icon(Icons.Rounded.SwapVert, null) },
                                onClick = { menu = false; session.setAll { it.asReversed().toList() } },
                            )
                            DropdownMenuItem(
                                text = { Text("Discard all pages", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; confirmClear = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (pages.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Button(
                        onClick = { showExport = true },
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(54.dp),
                    ) {
                        Icon(Icons.Rounded.PictureAsPdf, null)
                        Spacer(Modifier.width(10.dp))
                        Text("Create searchable PDF", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        },
    ) { padding ->
        if (pages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Rounded.AddAPhoto,
                    title = "No pages yet",
                    body = "Scan pages with the camera or add photos you already took.",
                    action = "Open camera",
                    onAction = onAddPages,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(108.dp),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(pages, key = { _, p -> p.id }) { index, page ->
                    Column(
                        Modifier.clickable { onEditPage(page.id) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PageThumbnail(session.dir, page, Modifier.fillMaxWidth().aspectRatio(0.72f), maxSide = 420)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                                .padding(horizontal = 10.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }

    busy?.let { BusyDialog(it) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Discard all ${pages.size} pages?") },
            text = { Text("The photos will be deleted from this phone.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; session.clear(); onBack() }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }

    if (showExport) {
        ExportDialog(
            pageCount = pages.size,
            onDismiss = { showExport = false },
            onStart = { title, langs, quality, style, font ->
                showExport = false
                AppGraph.settings.update { it.copy(ocrLanguages = langs, quality = quality, outputStyle = style, fontChoice = font) }
                if (OcrWorker.enqueue(context, title, langs, quality, style, font)) {
                    Toast.makeText(context, "Creating “$title” in the background", Toast.LENGTH_SHORT).show()
                    onExported()
                } else {
                    Toast.makeText(context, "Could not start — please try again", Toast.LENGTH_LONG).show()
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ExportDialog(
    pageCount: Int,
    onDismiss: () -> Unit,
    onStart: (String, List<String>, PdfQuality, OutputStyle, FontChoice) -> Unit,
) {
    val context = LocalContext.current
    val settings = AppGraph.settings.value
    val installed = remember { OcrLanguages.installed(context) }
    var title by remember { mutableStateOf(defaultScanTitle()) }
    var langs by remember { mutableStateOf(settings.ocrLanguages.filter { it in installed }.ifEmpty { listOf("eng") }) }
    var quality by remember { mutableStateOf(settings.quality) }
    var style by remember { mutableStateOf(settings.outputStyle) }
    var font by remember { mutableStateOf(settings.fontChoice) }
    val digitalPossible = langs.all { it in PageRebuilder.SUPPORTED_LANGUAGES }
    var pendingStart by remember { mutableStateOf(false) }

    fun launch() = onStart(title.trim().ifBlank { defaultScanTitle() }, langs, quality, style, font)

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pendingStart) launch()
    }

    fun start() {
        val needsAsk = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsk) {
            pendingStart = true
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launch()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.PictureAsPdf, null) },
        title = { Text("Create searchable PDF") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text("Text language", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Pick every language that appears in the book. More languages in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    installed.sortedBy { OcrLanguages.nameOf(it) }.forEach { code ->
                        val on = code in langs
                        FilterChip(
                            selected = on,
                            onClick = { langs = if (on) (langs - code).ifEmpty { langs } else langs + code },
                            label = { Text(OcrLanguages.nameOf(code)) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Result", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    OutputStyle.entries.forEachIndexed { i, o ->
                        SegmentedButton(
                            selected = o == style,
                            onClick = { style = o },
                            enabled = o == OutputStyle.PHOTO || digitalPossible,
                            shape = SegmentedButtonDefaults.itemShape(i, OutputStyle.entries.size),
                        ) { Text(o.label) }
                    }
                }
                Text(
                    if (style == OutputStyle.DIGITAL && !digitalPossible) "Digital text supports Latin, Greek and Cyrillic " +
                        "scripts; this book will keep its original photos." else style.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (style == OutputStyle.DIGITAL && digitalPossible) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FontChoice.entries.forEach { f ->
                            FilterChip(selected = f == font, onClick = { font = f }, label = { Text(f.label) })
                        }
                    }
                }
                if (style == OutputStyle.PHOTO || !digitalPossible) {
                Spacer(Modifier.height(12.dp))
                Text("Image quality", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PdfQuality.entries.forEachIndexed { i, q ->
                        SegmentedButton(
                            selected = q == quality,
                            onClick = { quality = q },
                            shape = SegmentedButtonDefaults.itemShape(i, PdfQuality.entries.size),
                        ) { Text(q.label) }
                    }
                }
                Text(
                    quality.hint + " · OCR always reads the full-resolution photo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$pageCount page${if (pageCount == 1) "" else "s"} · roughly ${estimate(pageCount)} on a recent phone",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(0.dp))
            }
        },
        confirmButton = { Button(onClick = { start() }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun estimate(pages: Int): String {
    val seconds = pages * 6
    return if (seconds < 90) "$seconds seconds" else "${(seconds + 30) / 60} minutes"
}
