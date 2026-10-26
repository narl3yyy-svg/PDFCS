package com.narl3yyy.pdfcs.ui.screens

import com.narl3yyy.pdfcs.ui.components.rememberLoaded
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.automirrored.rounded.RotateLeft
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.narl3yyy.pdfcs.AppGraph
import com.narl3yyy.pdfcs.camera.Look
import com.narl3yyy.pdfcs.library.ScanPage
import com.narl3yyy.pdfcs.camera.PageImage
import com.narl3yyy.pdfcs.camera.PagePreviews
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditorScreen(pageId: String, onBack: () -> Unit) {
    val session = AppGraph.session
    val pages by session.pages.collectAsState()
    var currentId by remember { mutableStateOf(pageId) }
    val index = pages.indexOfFirst { it.id == currentId }
    val page = pages.getOrNull(index)
    var cropping by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(page == null) { if (page == null) onBack() }
    if (page == null) return

    if (cropping) {
        CropEditor(
            page = page,
            onCancel = { cropping = false },
            onApply = { quad ->
                session.update(page.copy(quad = quad))
                cropping = false
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Page ${index + 1} of ${pages.size}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    IconButton(enabled = index > 0, onClick = { currentId = pages[index - 1].id }) {
                        Icon(Icons.Rounded.ChevronLeft, "Previous page")
                    }
                    IconButton(enabled = index < pages.lastIndex, onClick = { currentId = pages[index + 1].id }) {
                        Icon(Icons.Rounded.ChevronRight, "Next page")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(vertical = 8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Look.entries.forEach { f ->
                            FilterChip(
                                selected = page.look == f,
                                onClick = { session.update(page.copy(look = f)) },
                                label = { Text(f.label) },
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        ToolButton(Icons.Rounded.Crop, "Crop") { cropping = true }
                        ToolButton(Icons.AutoMirrored.Rounded.RotateLeft, "Left") {
                            session.update(page.copy(rotation = (page.rotation + 270) % 360))
                        }
                        ToolButton(Icons.AutoMirrored.Rounded.RotateRight, "Right") {
                            session.update(page.copy(rotation = (page.rotation + 90) % 360))
                        }
                        ToolButton(Icons.Rounded.ContentCut, "Split") {
                            val (l, r) = PageImage.splitQuad(page.quad)
                            val left = page.copy(id = UUID.randomUUID().toString(), quad = l)
                            val right = page.copy(id = UUID.randomUUID().toString(), quad = r)
                            session.replace(page.id, listOf(left, right))
                            currentId = left.id
                        }
                        ToolButton(Icons.Rounded.KeyboardDoubleArrowLeft, "Earlier", enabled = index > 0) {
                            session.move(page.id, -1)
                        }
                        ToolButton(Icons.Rounded.KeyboardDoubleArrowRight, "Later", enabled = index < pages.lastIndex) {
                            session.move(page.id, 1)
                        }
                        ToolButton(Icons.Rounded.Delete, "Delete") { confirmDelete = true }
                    }
                }
            }
        },
    ) { padding ->
        val bmp by rememberLoaded(PagePreviews.cached(page, 1600), page.renderKey) { PagePreviews.load(session.dir, page, 1600) }
        Box(
            Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val b = bmp
            if (b == null) {
                CircularProgressIndicator()
            } else {
                Image(b.asImageBitmap(), "Page preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this page?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val next = pages.getOrNull(index + 1) ?: pages.getOrNull(index - 1)
                    session.remove(page.id)
                    if (next != null) currentId = next.id else onBack()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, label) }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}

/** Drag the four corners onto the page's corners; the page is then straightened. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CropEditor(page: ScanPage, onCancel: () -> Unit, onApply: (List<Float>?) -> Unit) {
    val session = AppGraph.session
    val src by rememberLoaded<Bitmap>(null, page.source) { PagePreviews.loadSource(session.fileOf(page), 1600) }
    var quad by remember { mutableStateOf(page.quad ?: insetQuad()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crop & straighten") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Cancel") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = { quad = ScanPage.FULL_QUAD }, modifier = Modifier.weight(1f)) { Text("Whole photo") }
                    Button(
                        onClick = { onApply(if (quad == ScanPage.FULL_QUAD) null else quad) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Apply") }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).background(Color(0xFF111111)).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val b = src
            if (b == null) {
                CircularProgressIndicator()
            } else {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val ratio = b.width.toFloat() / b.height
                    Box(Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < maxWidth / maxHeight)) {
                        Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        QuadOverlay(quad) { quad = it }
                    }
                }
            }
        }
    }
}

private fun insetQuad() = listOf(0.06f, 0.06f, 0.94f, 0.06f, 0.94f, 0.94f, 0.06f, 0.94f)

@Composable
private fun QuadOverlay(quad: List<Float>, onChange: (List<Float>) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    var dragging by remember { mutableStateOf(-1) }
    val latest by androidx.compose.runtime.rememberUpdatedState(quad)
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val q = latest
                        var best = -1
                        var bestD = Float.MAX_VALUE
                        for (i in 0 until 4) {
                            val dx = q[i * 2] * size.width - pos.x
                            val dy = q[i * 2 + 1] * size.height - pos.y
                            val d = dx * dx + dy * dy
                            if (d < bestD) { bestD = d; best = i }
                        }
                        dragging = if (bestD < (64.dp.toPx() * 64.dp.toPx())) best else -1
                    },
                    onDragEnd = { dragging = -1 },
                    onDragCancel = { dragging = -1 },
                    onDrag = { change, amount ->
                        val i = dragging
                        if (i >= 0) {
                            change.consume()
                            val q = latest.toMutableList()
                            q[i * 2] = (q[i * 2] + amount.x / size.width).coerceIn(0f, 1f)
                            q[i * 2 + 1] = (q[i * 2 + 1] + amount.y / size.height).coerceIn(0f, 1f)
                            onChange(q)
                        }
                    },
                )
            },
    ) {
        val pts = List(4) { Offset(quad[it * 2] * size.width, quad[it * 2 + 1] * size.height) }
        val outline = Path().apply {
            moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close()
        }
        val shade = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
            addPath(outline)
        }
        drawPath(shade, Color.Black.copy(alpha = 0.5f))
        drawPath(outline, accent, style = Stroke(2.5.dp.toPx()))
        pts.forEachIndexed { i, p ->
            drawCircle(Color.White, radius = if (i == dragging) 16.dp.toPx() else 12.dp.toPx(), center = p)
            drawCircle(accent, radius = if (i == dragging) 12.dp.toPx() else 8.dp.toPx(), center = p)
        }
    }
}
