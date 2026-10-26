package com.narl3yyy.pdfcs.ui.inspect

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.narl3yyy.pdfcs.AppGraph
import com.narl3yyy.pdfcs.inspect.DocumentInfo
import com.narl3yyy.pdfcs.inspect.Metadata
import com.narl3yyy.pdfcs.inspect.Report
import com.narl3yyy.pdfcs.ui.formatBytes
import kotlinx.coroutines.launch
import java.io.File

/** Analyze document: properties, structure, fonts, text coverage, security and suggestions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(path: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(path) { File(path) }
    val session = remember(path) { AppGraph.sessionFor(file) }
    val version by session.version.collectAsState()
    var report by remember { mutableStateOf<Report?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var meta by remember { mutableStateOf(Metadata("", "", "", "")) }
    LaunchedEffect(version) {
        runCatching { session.read { DocumentInfo.report(it, file) } }
            .onSuccess { report = it; meta = it.metadata }
            .onFailure { error = it.message ?: "Could not read the document" }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Analyze document") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
        )
    }) { padding ->
        val r = report
        if (r == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (error != null) Text(error!!) else CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (r.suggestions.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Lightbulb, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Suggestions", style = MaterialTheme.typography.titleSmall)
                        }
                        r.suggestions.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Section("Properties")
            OutlinedTextField(meta.title, { meta = meta.copy(title = it) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(meta.author, { meta = meta.copy(author = it) }, label = { Text("Author") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(meta.subject, { meta = meta.copy(subject = it) }, label = { Text("Subject") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(meta.keywords, { meta = meta.copy(keywords = it) }, label = { Text("Keywords") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(enabled = meta != r.metadata, onClick = {
                scope.launch {
                    runCatching { session.edit("Edit properties") { DocumentInfo.setMetadata(it, meta) } }
                        .onSuccess { Toast.makeText(context, "Properties saved", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context, it.message ?: "Could not save", Toast.LENGTH_LONG).show() }
                }
            }) { Text("Save properties") }

            Section("Document")
            Fact("Pages", "${r.pages}")
            Fact("Page size", r.pageSizes.joinToString("\n"))
            Fact("File size", formatBytes(r.fileSize))
            Fact("PDF version", "%.1f".format(r.pdfVersion))
            r.creator?.let { Fact("Created with", it) }
            r.producer?.let { Fact("Produced by", it) }
            r.created?.let { Fact("Created", it) }
            r.modified?.let { Fact("Modified", it) }

            Section("Text")
            Fact("Searchable pages", "${r.pagesWithText} of ${r.pages}")
            Fact("Words", "%,d".format(r.words))
            Fact("Bookmarks", if (r.bookmarks) "Yes" else "None")
            Fact("Tagged for accessibility", if (r.tagged) "Yes" else "No")

            Section("Content")
            Fact("Images", "${r.images}")
            Fact("Comments", "${r.comments}")
            Fact("Form fields", "${r.formFields}")

            Section("Fonts")
            if (r.fonts.isEmpty()) Text("No fonts (the pages are images).", style = MaterialTheme.typography.bodyMedium)
            r.fonts.forEach { Fact(it.name, if (it.embedded) "Embedded" else "Not embedded") }

            Section("Security")
            Text(r.security, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.45f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.55f))
    }
}
