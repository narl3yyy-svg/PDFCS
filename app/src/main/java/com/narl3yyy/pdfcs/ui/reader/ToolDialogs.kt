package com.narl3yyy.pdfcs.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.narl3yyy.pdfcs.annot.CommentInfo
import com.narl3yyy.pdfcs.core.PageRanges
import com.narl3yyy.pdfcs.crypt.Permissions
import com.narl3yyy.pdfcs.edit.MarkPosition
import com.narl3yyy.pdfcs.engine.Tool
import com.narl3yyy.pdfcs.engine.ToolGroup
import com.narl3yyy.pdfcs.optimize.Reduction
import kotlin.math.roundToInt

/** The All Tools panel: every tool, grouped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsSheet(onDismiss: () -> Unit, onTool: (Tool) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.heightIn(max = 640.dp)) {
            for (group in ToolGroup.entries) {
                val tools = Tool.entries.filter { it.group == group }
                if (tools.isEmpty()) continue
                item(group.name) {
                    Text(
                        group.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(tools, key = { it.id }) { tool ->
                    ListItem(
                        headlineContent = { Text(tool.label) },
                        supportingContent = { Text(tool.hint) },
                        leadingContent = { Icon(toolIcon(tool), null) },
                        modifier = Modifier.clickable { onDismiss(); onTool(tool) },
                    )
                }
            }
            item("end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** The Comments panel: every comment, with a delete button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(comments: List<CommentInfo>?, onDismiss: () -> Unit, onOpen: (CommentInfo) -> Unit, onDelete: (CommentInfo) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            if (comments == null) "Loading comments…" else "${comments.size} comment${if (comments.size == 1) "" else "s"}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            items(comments.orEmpty(), key = { "${it.page}:${it.index}" }) { c ->
                ListItem(
                    headlineContent = { Text("${c.kind} · page ${c.page + 1}") },
                    supportingContent = c.text?.takeIf { it.isNotBlank() }?.let { t -> { Text(t, maxLines = 3) } },
                    trailingContent = { IconButton(onClick = { onDelete(c) }) { Icon(Icons.Rounded.Delete, "Delete comment") } },
                    modifier = Modifier.clickable { onOpen(c) },
                )
                HorizontalDivider()
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun PasswordDialog(wrong: Boolean, onDismiss: () -> Unit, onPassword: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Lock, null) },
        title = { Text("Password needed") },
        text = {
            Column {
                Text(if (wrong) "That password didn’t work. Try again." else "This document is protected. Enter its password to open it.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    pw, { pw = it }, singleLine = true, label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPassword(pw) }, enabled = pw.isNotEmpty()) { Text("Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ProtectDialog(onDismiss: () -> Unit, onProtect: (String, String, Permissions) -> Unit) {
    var open by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var perms by remember { mutableStateOf(Permissions()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Lock, null) },
        title = { Text("Protect with password") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Encrypted with AES-256. Keep the passwords safe: a forgotten password cannot be recovered.",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(open, { open = it }, singleLine = true, label = { Text("Password to open (optional)") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(owner, { owner = it }, singleLine = true, label = { Text("Permissions password") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("Without the permissions password, others may:", style = MaterialTheme.typography.titleSmall)
                Check("Print", perms.print) { perms = perms.copy(print = it) }
                Check("Copy text", perms.copy) { perms = perms.copy(copy = it) }
                Check("Comment", perms.annotate) { perms = perms.copy(annotate = it) }
                Check("Fill forms", perms.fillForms) { perms = perms.copy(fillForms = it) }
                Check("Edit pages", perms.modify) { perms = perms.copy(modify = it) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onProtect(open, owner, perms) }, enabled = owner.length >= 4 && owner != open) { Text("Protect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Check(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(value, onChange)
        Text(label)
    }
}

@Composable
fun WatermarkDialog(onDismiss: () -> Unit, onApply: (String, Float) -> Unit) {
    var text by remember { mutableStateOf("CONFIDENTIAL") }
    var opacity by remember { mutableFloatStateOf(0.18f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Watermark") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Text") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text("Opacity ${(opacity * 100).roundToInt()}%")
                Slider(opacity, { opacity = it }, valueRange = 0.05f..0.6f)
            }
        },
        confirmButton = { TextButton(onClick = { onApply(text, opacity) }, enabled = text.isNotBlank()) { Text("Apply to all pages") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeaderFooterDialog(onDismiss: () -> Unit, onApply: (String, MarkPosition, Int) -> Unit) {
    var template by remember { mutableStateOf("Page {page} of {pages}") }
    var position by remember { mutableStateOf(MarkPosition.BOTTOM_CENTER) }
    var first by remember { mutableStateOf("1") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Page numbers & header") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(template, { template = it }, singleLine = true, label = { Text("Text") }, modifier = Modifier.fillMaxWidth())
                Text("{page} is the page number, {pages} the page count.", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MarkPosition.entries.forEach { p -> FilterChip(p == position, { position = p }, label = { Text(p.label) }) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(first, { first = it.filter(Char::isDigit).take(5) }, singleLine = true, label = { Text("First number") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(template, position, first.toIntOrNull() ?: 1) }, enabled = template.isNotBlank()) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OptimizeDialog(onDismiss: () -> Unit, onApply: (Reduction) -> Unit) {
    var level by remember { mutableStateOf(Reduction.MEDIUM) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reduce file size") },
        text = {
            Column {
                Text("Large photos are downsampled and recompressed. Text, line art and transparent images are left alone.")
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Reduction.entries.forEach { r -> FilterChip(r == level, { level = r }, label = { Text(r.label) }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(level) }) { Text("Reduce") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks for pages ("1-3, 5"). */
@Composable
fun PagesDialog(title: String, message: String, pageCount: Int, confirm: String, initial: String = "", onDismiss: () -> Unit, onPages: (List<Int>) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val parsed = PageRanges.parse(text, pageCount)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Pages (1–$pageCount)") },
                    placeholder = { Text("e.g. 1-3, 5") }, isError = text.isNotBlank() && parsed == null, modifier = Modifier.fillMaxWidth())
                if (parsed != null) Text("${parsed.size} page${if (parsed.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onPages) }, enabled = !parsed.isNullOrEmpty()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SplitDialog(pageCount: Int, onDismiss: () -> Unit, onSplit: (Int) -> Unit) {
    var every by remember { mutableStateOf("10") }
    val n = every.toIntOrNull()?.takeIf { it in 1 until pageCount.coerceAtLeast(2) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Split") },
        text = {
            Column {
                Text("Divide the $pageCount pages into documents of equal size.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(every, { every = it.filter(Char::isDigit).take(5) }, singleLine = true, label = { Text("Pages per part") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = n == null, modifier = Modifier.fillMaxWidth())
                if (n != null) Text("${(pageCount + n - 1) / n} parts", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { n?.let(onSplit) }, enabled = n != null) { Text("Split") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Text for a sticky note or for text added to the page. */
@Composable
fun TextEntryDialog(title: String, withSize: Boolean, onDismiss: () -> Unit, onDone: (String, Float) -> Unit) {
    var text by remember { mutableStateOf("") }
    var size by remember { mutableFloatStateOf(12f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, minLines = 2, modifier = Modifier.fillMaxWidth())
                if (withSize) {
                    Spacer(Modifier.height(8.dp))
                    Text("Size ${size.roundToInt()} pt")
                    Slider(size, { size = it }, valueRange = 6f..48f)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text, size) }, enabled = text.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

