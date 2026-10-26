package com.narl3yyy.pdfcs.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.narl3yyy.pdfcs.AppGraph
import com.narl3yyy.pdfcs.camera.Look
import com.narl3yyy.pdfcs.library.PdfQuality
import com.narl3yyy.pdfcs.ocr.OcrLanguages
import com.narl3yyy.pdfcs.ui.formatBytes
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onAbout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by AppGraph.settings.state.collectAsState()
    var installed by remember { mutableStateOf(OcrLanguages.installed(context)) }
    val downloading = remember { mutableStateMapOf<String, Float>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 32.dp)) {
            item { Section("Scanning") }
            item {
                ListItem(
                    headlineContent = { Text("Default look for new pages") },
                    supportingContent = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Look.entries.forEach { f ->
                                FilterChip(
                                    selected = settings.defaultLook == f,
                                    onClick = { AppGraph.settings.update { it.copy(defaultLook = f) } },
                                    label = { Text(f.label) },
                                )
                            }
                        }
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Find page edges automatically") },
                    supportingContent = { Text("Crops away the table or background around the page. You can still adjust it.") },
                    trailingContent = {
                        Switch(settings.autoCrop, { v -> AppGraph.settings.update { it.copy(autoCrop = v) } })
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Volume keys take a photo") },
                    supportingContent = { Text("Hold the book open with one hand and shoot with the other") },
                    trailingContent = {
                        Switch(settings.volumeShutter, { v -> AppGraph.settings.update { it.copy(volumeShutter = v) } })
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Shutter sound") },
                    trailingContent = {
                        Switch(settings.shutterSound, { v -> AppGraph.settings.update { it.copy(shutterSound = v) } })
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Auto-capture interval") },
                    supportingContent = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(2, 3, 4, 5, 7, 10).forEach { s ->
                                FilterChip(
                                    selected = settings.autoCaptureSeconds == s,
                                    onClick = { AppGraph.settings.update { it.copy(autoCaptureSeconds = s) } },
                                    label = { Text("${s}s") },
                                )
                            }
                        }
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("PDF image quality") },
                    supportingContent = {
                        Column {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PdfQuality.entries.forEach { q ->
                                    FilterChip(
                                        selected = settings.quality == q,
                                        onClick = { AppGraph.settings.update { it.copy(quality = q) } },
                                        label = { Text(q.label) },
                                    )
                                }
                            }
                            Text(settings.quality.hint)
                        }
                    },
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { Section("OCR languages") }
            item {
                Text(
                    "Text recognition runs entirely on your phone with Tesseract. English is built in; " +
                        "other languages are downloaded once from the official Tesseract project.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(OcrLanguages.ALL, key = { it.code }) { lang ->
                val isInstalled = lang.code in installed
                val progress = downloading[lang.code]
                ListItem(
                    headlineContent = { Text(lang.name) },
                    supportingContent = {
                        Text(
                            when {
                                lang.code == OcrLanguages.BUNDLED -> "Built in"
                                progress != null -> "Downloading… ${(progress * 100).toInt()}%"
                                isInstalled -> "Installed · ${formatBytes(OcrLanguages.sizeOnDisk(context, lang.code))}"
                                else -> "Not installed"
                            },
                        )
                    },
                    leadingContent = {
                        if (isInstalled) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        else Spacer(Modifier.size(24.dp))
                    },
                    trailingContent = {
                        Box(contentAlignment = Alignment.Center) {
                            when {
                                progress != null -> CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(28.dp))
                                lang.code == OcrLanguages.BUNDLED -> Unit
                                isInstalled -> IconButton(onClick = {
                                    OcrLanguages.delete(context, lang.code)
                                    installed = OcrLanguages.installed(context)
                                    AppGraph.settings.update { s -> s.copy(ocrLanguages = s.ocrLanguages - lang.code) }
                                }) { Icon(Icons.Rounded.Delete, "Remove ${lang.name}") }
                                else -> IconButton(onClick = {
                                    downloading[lang.code] = 0f
                                    scope.launch {
                                        runCatching {
                                            OcrLanguages.download(context, lang.code) { p -> downloading[lang.code] = p }
                                        }.onFailure {
                                            Toast.makeText(context, "Download failed: ${it.message}", Toast.LENGTH_LONG).show()
                                        }
                                        downloading.remove(lang.code)
                                        installed = OcrLanguages.installed(context)
                                    }
                                }) { Icon(Icons.Rounded.CloudDownload, "Download ${lang.name}") }
                            }
                        }
                    },
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                ListItem(
                    headlineContent = { Text("About PDFCS") },
                    supportingContent = { Text("Version, licence and credits") },
                    leadingContent = { Icon(Icons.Rounded.Info, null) },
                    modifier = Modifier.clickable(onClick = onAbout),
                )
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}
