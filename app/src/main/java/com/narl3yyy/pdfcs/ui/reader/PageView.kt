package com.narl3yyy.pdfcs.ui.reader

import com.narl3yyy.pdfcs.ui.components.rememberLoaded
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.narl3yyy.pdfcs.reader.PdfPages
import com.narl3yyy.pdfcs.ui.theme.HighlightColor
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a touch on the page does. */
enum class Mode { READ, DRAW, NOTE, TEXT }

/** A freehand stroke in display-normalised page coordinates. */
class InkStroke(val colour: Int, val widthPt: Float, val points: List<Pair<Float, Float>>)

/** Overlays drawn on top of a page. */
class PageOverlay(
    val searchHits: List<RectF> = emptyList(),
    val activeHit: List<RectF> = emptyList(),
    val selection: List<RectF> = emptyList(),
    val strokes: List<InkStroke> = emptyList(),
)

/**
 * One page: pinch or double-tap to zoom, pan when zoomed (a horizontal swipe at the edge goes to
 * the pager). Long-press and drag selects text; in drawing mode one finger draws; in note and
 * text mode a tap places. All page positions are display-normalised (0..1, top-left).
 */
@Composable
fun PageView(
    pages: PdfPages,
    index: Int,
    mode: Mode,
    overlay: PageOverlay,
    onTap: () -> Unit,
    onPlace: (Float, Float) -> Unit,
    onSelectStart: (Float, Float) -> Unit,
    onSelectMove: (Float, Float) -> Unit,
    onStroke: (List<Pair<Float, Float>>) -> Unit,
    penColour: Int,
    penWidthPt: Float,
) {
    val density = LocalDensity.current
    val currentMode by rememberUpdatedState(mode)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val renderWidth = with(density) { (maxWidth.toPx() * 2f).roundToInt().coerceIn(600, 2400) }
        val bmp by rememberLoaded(pages.cached(index, renderWidth), pages, index, renderWidth) { pages.render(index, renderWidth) }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        fun clamp(o: Offset, s: Float): Offset {
            val mx = boxW * (s - 1f) / 2f
            val my = boxH * (s - 1f) / 2f
            return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
        }
        val pageSize = pages.sizes.getOrNull(index) ?: android.util.SizeF(612f, 792f)
        val ratio = pageSize.width / pageSize.height
        var live by remember { mutableStateOf<List<Offset>>(emptyList()) }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(index) {
                    detectTapGestures(
                        onTap = { if (currentMode == Mode.READ) onTap() },
                        onDoubleTap = { tap ->
                            if (currentMode != Mode.READ) return@detectTapGestures
                            if (scale > 1.05f) {
                                scale = 1f; offset = Offset.Zero
                            } else {
                                val s = 2.5f
                                offset = clamp((Offset(boxW / 2, boxH / 2) - tap) * (s - 1f), s)
                                scale = s
                            }
                        },
                    )
                }
                .pointerInput(index) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.any { it.isConsumed }) continue
                            val multi = event.changes.count { it.pressed } > 1
                            if (multi || (scale > 1.01f && currentMode == Mode.READ)) {
                                val newScale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                                val pan = event.calculatePan()
                                val newOffset = clamp(offset + pan, newScale)
                                // At the left/right edge a horizontal swipe belongs to the pager.
                                val atEdge = !multi && abs(pan.x) > abs(pan.y) && newOffset.x == offset.x
                                if (!atEdge) {
                                    scale = newScale
                                    offset = if (newScale <= 1.01f) Offset.Zero else newOffset
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .padding(8.dp)
                    .aspectRatio(ratio, matchHeightConstraintsFirst = ratio < boxW / boxH)
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    }
                    .background(Color.White)
                    // Pointer positions here are in the page's own (untransformed) coordinates.
                    .pointerInput(index, mode) {
                        fun norm(o: Offset) = (o.x / size.width).coerceIn(0f, 1f) to (o.y / size.height).coerceIn(0f, 1f)
                        when (mode) {
                            Mode.READ -> detectDragGesturesAfterLongPress(
                                onDragStart = { o -> val (u, v) = norm(o); onSelectStart(u, v) },
                                onDrag = { change, _ -> change.consume(); val (u, v) = norm(change.position); onSelectMove(u, v) },
                            )
                            Mode.DRAW -> detectDragGestures(
                                onDragStart = { o -> live = listOf(o) },
                                onDrag = { change, _ -> change.consume(); live = live + change.position },
                                onDragEnd = {
                                    if (live.size >= 2) onStroke(live.map { norm(it) })
                                    live = emptyList()
                                },
                                onDragCancel = { live = emptyList() },
                            )
                            Mode.NOTE, Mode.TEXT -> detectTapGestures { o -> val (u, v) = norm(o); onPlace(u, v) }
                        }
                    },
            ) {
                val b = bmp
                if (b != null) {
                    Image(b.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                } else {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                Canvas(Modifier.fillMaxSize()) {
                    fun rect(r: RectF, c: Color, stroke: Boolean = false) {
                        val tl = Offset(r.left * this.size.width, r.top * this.size.height)
                        val sz = Size(r.width() * this.size.width, r.height() * this.size.height)
                        if (stroke) drawRect(c, tl, sz, style = Stroke(1.5.dp.toPx())) else drawRect(c, tl, sz)
                    }
                    overlay.searchHits.forEach { rect(it, HighlightColor.copy(alpha = 0.45f)) }
                    overlay.activeHit.forEach { rect(it, Color(0xFFFF8A00).copy(alpha = 0.45f)); rect(it, Color(0xFFE65100), true) }
                    overlay.selection.forEach { rect(it, Color(0xFF2196F3).copy(alpha = 0.30f)) }
                    val ptPerPx = this.size.width / pageSize.width
                    for (s in overlay.strokes) drawStroke(s.points.map { (u, v) -> Offset(u * this.size.width, v * this.size.height) }, Color(s.colour), s.widthPt * ptPerPx)
                    if (live.size >= 2) drawStroke(live, Color(penColour), penWidthPt * ptPerPx)
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStroke(points: List<Offset>, colour: Color, width: Float) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (p in points.drop(1)) lineTo(p.x, p.y)
    }
    drawPath(path, colour, style = Stroke(width.coerceAtLeast(1f), cap = StrokeCap.Round, join = StrokeJoin.Round))
}
