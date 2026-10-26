package com.narl3yyy.pdfcs.ui.inspect

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.narl3yyy.pdfcs.forms.FormField
import com.narl3yyy.pdfcs.forms.Forms
import com.narl3yyy.pdfcs.ui.components.EmptyState
import kotlinx.coroutines.launch
import java.io.File

/** Fill Form: every field of the document's form, filled in place. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FormScreen(path: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(path) { File(path) }
    val session = remember(path) { AppGraph.sessionFor(file) }
    val version by session.version.collectAsState()
    var fields by remember { mutableStateOf<List<FormField>?>(null) }
    val values = remember { mutableStateMapOf<String, String>() }
    var confirmFlatten by remember { mutableStateOf(false) }
    LaunchedEffect(version) {
        fields = runCatching { session.read { Forms.fields(it) } }.getOrDefault(emptyList())
        values.clear()
        fields?.forEach { values[it.name] = it.value }
    }
    fun save(flatten: Boolean) = scope.launch {
        val changed = fields.orEmpty().filter { values[it.name] != it.value }.associate { it.name to values[it.name].orEmpty() }
        runCatching {
            session.edit(if (flatten) "Flatten form" else "Fill form") { doc ->
                if (changed.isNotEmpty()) Forms.fill(doc, changed)
                if (flatten) Forms.flatten(doc)
            }
        }.onSuccess { Toast.makeText(context, if (flatten) "Form flattened" else "Form saved", Toast.LENGTH_SHORT).show(); if (flatten) onBack() }
            .onFailure { Toast.makeText(context, it.message ?: "Could not save the form", Toast.LENGTH_LONG).show() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fill form") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            if (!fields.isNullOrEmpty()) Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { confirmFlatten = true }, modifier = Modifier.weight(1f)) { Text("Flatten") }
                    Button(onClick = { save(false) }, modifier = Modifier.weight(1f)) { Text("Save") }
                }
            }
        },
    ) { padding ->
        val list = fields
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyState(Icons.Rounded.EditNote, "No form fields", "This document has no fillable form.", Modifier.padding(padding).padding(top = 48.dp))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, padding.calculateBottomPadding() + 16.dp)) {
                items(list, key = { it.name }) { f ->
                    val v = values[f.name].orEmpty()
                    when (f.kind) {
                        FormField.Kind.TEXT -> OutlinedTextField(
                            v, { values[f.name] = it }, label = { Text(f.label) }, enabled = !f.readOnly,
                            singleLine = !f.multiline, minLines = if (f.multiline) 3 else 1,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                        FormField.Kind.CHECKBOX -> ListItem(
                            headlineContent = { Text(f.label) },
                            trailingContent = { Switch(v == "on", { values[f.name] = if (it) "on" else "off" }, enabled = !f.readOnly) },
                        )
                        FormField.Kind.CHOICE, FormField.Kind.RADIO -> Column2(f.label) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                f.options.forEach { o -> FilterChip(v == o, { if (!f.readOnly) values[f.name] = o }, label = { Text(o) }) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmFlatten) AlertDialog(
        onDismissRequest = { confirmFlatten = false },
        title = { Text("Flatten the form?") },
        text = { Text("The values become part of the page and can no longer be edited. You can undo this in the reader.") },
        confirmButton = { TextButton(onClick = { confirmFlatten = false; save(true) }) { Text("Flatten") } },
        dismissButton = { TextButton(onClick = { confirmFlatten = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Column2(label: String, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        content()
    }
}
