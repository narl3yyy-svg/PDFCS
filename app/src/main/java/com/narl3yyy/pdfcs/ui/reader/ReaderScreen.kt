package com.narl3yyy.pdfcs.ui.reader

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FormatColorText
import androidx.compose.material.icons.rounded.FormatStrikethrough
import androidx.compose.material.icons.rounded.FormatUnderlined
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.narl3yyy.pdfcs.AppGraph
import com.narl3yyy.pdfcs.annot.Annotations
import com.narl3yyy.pdfcs.annot.CommentInfo
import com.narl3yyy.pdfcs.annot.Markup
import com.narl3yyy.pdfcs.crypt.Protection
import com.narl3yyy.pdfcs.edit.PageMarks
import com.narl3yyy.pdfcs.engine.PasswordRequired
import com.narl3yyy.pdfcs.engine.Tool
import com.narl3yyy.pdfcs.export.Exporters
import com.narl3yyy.pdfcs.library.PdfDoc
import com.narl3yyy.pdfcs.optimize.Optimizer
import com.narl3yyy.pdfcs.organize.PageOps
import com.narl3yyy.pdfcs.reader.DisplayCopy
import com.narl3yyy.pdfcs.reader.PdfPages
import com.narl3yyy.pdfcs.reader.PdfTextIndex
import com.narl3yyy.pdfcs.reader.SearchHit
import com.narl3yyy.pdfcs.ui.components.EmptyState
import com.narl3yyy.pdfcs.ui.screens.TextInputDialog
import com.narl3yyy.pdfcs.ui.screens.share
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private enum class Dialog { PASSWORD, PROTECT, WATERMARK, HEADER_FOOTER, OPTIMIZE, EXTRACT, SPLIT, GOTO, PAGE_TEXT, UNPROTECT }

private data class Selection(val page: Int, val anchor: Int, val range: IntRange)

private val PEN_COLOURS = listOf(0xFF212121.toInt(), 0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0x99FFD54F.toInt())

/**
 * The reader and editor: read, search, select and copy text, comment, draw, and run every
 * document tool. Each edit is one undoable step of the document's session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    path: String,
    onBack: () -> Unit,
    onOrganize: (File) -> Unit,
    onInfo: (File) -> Unit,
    onFillForm: (File) -> Unit,
    onMakeSearchable: (File) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val file = remember(path) { File(path) }
    val session = remember(path) { AppGraph.sessionFor(file) }
    val version by session.version.collectAsState()
    val history by session.history.collectAsState()
    val marks = remember { PageMarks(context) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    // Protected documents: ask once, then render a decrypted copy kept in the cache.
    var locked by remember { mutableStateOf<Boolean?>(null) }
    var wrongPassword by remember { mutableStateOf(false) }
    LaunchedEffect(path) { locked = session.password == null && session.needsPassword() }
    var viewFile by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(locked, version) {
        if (locked != false) return@LaunchedEffect
        // Show the file at once; swap in the copy with comments drawn in when it is ready.
        if (session.password == null && viewFile == null) viewFile = file
        viewFile = runCatching { DisplayCopy.prepare(file, context.cacheDir, session.password) }
            .getOrElse { if (session.password == null) file else null }
    }
    val pages = remember(viewFile, version) { viewFile?.let { runCatching { PdfPages(it) }.getOrNull() } }
    DisposableEffect(pages) { onDispose { pages?.close() } }
    val pageCount = pages?.pageCount ?: 0

    var index by remember { mutableStateOf<PdfTextIndex?>(null) }
    var indexProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(viewFile, version) {
        val f = viewFile ?: return@LaunchedEffect
        index = null
        index = runCatching { PdfTextIndex.load(f, context.cacheDir) { d, t -> indexProgress = d.toFloat() / t } }.getOrNull()
    }

    val pager = rememberPagerState { pageCount }
    var mode by remember { mutableStateOf(Mode.READ) }
    var chrome by remember { mutableStateOf(true) }
    var selection by remember { mutableStateOf<Selection?>(null) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var showTools by remember { mutableStateOf(false) }
    var comments by remember { mutableStateOf<List<CommentInfo>?>(null) }
    var showComments by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    // Drawing
    val strokes = remember { mutableStateListOf<InkStroke>() }
    var strokePage by remember { mutableIntStateOf(-1) }
    var penColour by remember { mutableIntStateOf(PEN_COLOURS[1]) }
    var penWidth by remember { mutableFloatStateOf(2.5f) }
    var place by remember { mutableStateOf<Triple<Mode, Int, Pair<Float, Float>>?>(null) }
    // Search
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searched by remember { mutableStateOf<String?>(null) }
    var current by remember { mutableIntStateOf(0) }
    var showHits by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current

    /** Runs one undoable change on the document. */
    fun edit(label: String, done: String? = null, change: (PDDocument) -> Unit) {
        scope.launch {
            busy = "$label…"
            runCatching { session.edit(label, change) }
                .onSuccess { done?.let { scope.launch { snackbar.showSnackbar(it) } } }
                .onFailure { toast(it.message ?: "$label failed") }
            busy = null
        }
    }

    fun newDocument(title: String, make: (PDDocument, File) -> Unit) {
        scope.launch {
            busy = "$title…"
            runCatching {
                val target = AppGraph.documents.uniqueFile("${file.nameWithoutExtension} – $title")
                session.read { doc -> make(doc, target) }
                AppGraph.documents.refresh()
                target
            }.onSuccess { scope.launch { snackbar.showSnackbar("Saved “${it.nameWithoutExtension}” in your library") } }
                .onFailure { toast(it.message ?: "$title failed") }
            busy = null
        }
    }

    fun exportTo(uri: Uri?, what: String, write: suspend (java.io.OutputStream) -> Unit) {
        if (uri == null) return
        scope.launch {
            busy = "Exporting…"
            runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "w")!!.use { write(it) } }
            }.onSuccess { snackbar.showSnackbar("Exported $what") }.onFailure { toast(it.message ?: "Export failed") }
            busy = null
        }
    }

    val exportText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        exportTo(uri, "text") { out -> session.read { Exporters.text(it, out) } }
    }
    val exportWord = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    ) { uri -> exportTo(uri, "Word document") { out -> session.read { Exporters.docx(it, out) } } }
    val exportImages = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val f = viewFile
        if (f != null) exportTo(uri, "page images") { out -> Exporters.images(f, out) { d, t -> busy = "Rendering page $d of $t…" } }
    }
    val insertFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val tmp = File(context.cacheDir, "insert_${System.nanoTime()}.pdf")
            runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } } }
                val at = pager.currentPage + 1
                session.edit("Insert pages") { doc -> PageOps.insertFile(doc, tmp, at) }
            }.onSuccess { snackbar.showSnackbar("Pages inserted after page ${pager.currentPage + 1}") }
                .onFailure { toast(it.message ?: "Could not insert that file") }
            tmp.delete()
        }
    }

    fun loadComments() {
        comments = null
        scope.launch { comments = runCatching { session.read { Annotations.list(it) } }.getOrDefault(emptyList()) }
    }

    fun onTool(tool: Tool) {
        when (tool) {
            Tool.OCR -> onMakeSearchable(file)
            Tool.ORGANIZE -> onOrganize(file)
            Tool.INSERT_FILE -> insertFile.launch(arrayOf("application/pdf"))
            Tool.EXTRACT -> dialog = Dialog.EXTRACT
            Tool.SPLIT -> dialog = Dialog.SPLIT
            Tool.HIGHLIGHT -> scope.launch { snackbar.showSnackbar("Long-press a word, drag to select, then choose Highlight") }
            Tool.NOTE -> { mode = Mode.NOTE; scope.launch { snackbar.showSnackbar("Tap where the note should go") } }
            Tool.DRAW -> { mode = Mode.DRAW; strokes.clear(); strokePage = pager.currentPage }
            Tool.COMMENTS -> { loadComments(); showComments = true }
            Tool.ADD_TEXT -> { mode = Mode.TEXT; scope.launch { snackbar.showSnackbar("Tap where the text should start") } }
            Tool.FILL_FORM -> onFillForm(file)
            Tool.WATERMARK -> dialog = Dialog.WATERMARK
            Tool.HEADER_FOOTER -> dialog = Dialog.HEADER_FOOTER
            Tool.PROPERTIES, Tool.INFO -> onInfo(file)
            Tool.PROTECT -> dialog = Dialog.PROTECT
            Tool.UNPROTECT -> dialog = Dialog.UNPROTECT
            Tool.EXPORT_TEXT -> exportText.launch("${file.nameWithoutExtension}.txt")
            Tool.EXPORT_WORD -> exportWord.launch("${file.nameWithoutExtension}.docx")
            Tool.EXPORT_IMAGES -> exportImages.launch("${file.nameWithoutExtension} pages.zip")
            Tool.OPTIMIZE -> dialog = Dialog.OPTIMIZE
            Tool.SHARE -> share(context, listOf(PdfDoc(file, file.nameWithoutExtension, pageCount, file.length(), file.lastModified())))
        }
    }

    fun runSearch() {
        val idx = index ?: return
        keyboard?.hide()
        scope.launch {
            val result = withContext(Dispatchers.Default) { idx.search(query.trim()) }
            hits = result
            searched = query.trim()
            current = result.indexOfFirst { it.page >= pager.currentPage }.takeIf { it >= 0 } ?: 0
            result.getOrNull(current)?.let { pager.scrollToPage(it.page) }
        }
    }

    fun goToHit(i: Int) {
        if (hits.isEmpty()) return
        current = Math.floorMod(i, hits.size)
        scope.launch { pager.animateScrollToPage(hits[current].page) }
    }

    fun closeSearch() { searching = false; query = ""; hits = emptyList(); searched = null }

    BackHandler(enabled = searching || selection != null || mode != Mode.READ) {
        when {
            mode != Mode.READ -> { mode = Mode.READ; strokes.clear() }
            selection != null -> selection = null
            else -> closeSearch()
        }
    }
    LaunchedEffect(searching) { if (searching) runCatching { focus.requestFocus() } }
    LaunchedEffect(locked) { if (locked == true) dialog = Dialog.PASSWORD }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        topBar = {
            when {
                searching -> TopAppBar(
                    navigationIcon = { IconButton(onClick = { closeSearch() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search") } },
                    title = {
                        TextField(
                            value = query, onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            placeholder = { Text(if (index == null) "Indexing text…" else "Search in document") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            ),
                        )
                    },
                    actions = { IconButton(onClick = { runSearch() }, enabled = index != null && query.isNotBlank()) { Icon(Icons.Rounded.Search, "Search") } },
                )
                mode != Mode.READ -> TopAppBar(
                    navigationIcon = { IconButton(onClick = { mode = Mode.READ; strokes.clear() }) { Icon(Icons.Rounded.Close, "Cancel") } },
                    title = {
                        Text(when (mode) { Mode.DRAW -> "Draw"; Mode.NOTE -> "Tap to place a note"; Mode.TEXT -> "Tap to place text"; else -> "" })
                    },
                    actions = {
                        if (mode == Mode.DRAW) {
                            IconButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) {
                                Icon(Icons.AutoMirrored.Rounded.Undo, "Undo stroke")
                            }
                            IconButton(enabled = strokes.isNotEmpty(), onClick = {
                                val page = strokePage
                                val copy = strokes.toList()
                                mode = Mode.READ
                                strokes.clear()
                                edit("Draw", "Drawing added") { doc ->
                                    copy.groupBy { it.colour to it.widthPt }.forEach { (key, group) ->
                                        Annotations.addInk(doc, page, group.map { it.points }, key.first, key.second)
                                    }
                                }
                            }) { Icon(Icons.Rounded.Check, "Save drawing") }
                        }
                    },
                )
                chrome -> TopAppBar(
                    title = { Text(file.nameWithoutExtension, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                    actions = {
                        IconButton(onClick = { scope.launch { session.undo() } }, enabled = history.undoLabel != null) {
                            Icon(Icons.AutoMirrored.Rounded.Undo, "Undo ${history.undoLabel.orEmpty()}")
                        }
                        IconButton(onClick = { scope.launch { session.redo() } }, enabled = history.redoLabel != null) {
                            Icon(Icons.AutoMirrored.Rounded.Redo, "Redo ${history.redoLabel.orEmpty()}")
                        }
                        IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, "Search") }
                        IconButton(onClick = { showTools = true }) { Icon(Icons.Rounded.Apps, "All tools") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Page text") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.TextSnippet, null) },
                                    enabled = index != null, onClick = { menu = false; dialog = Dialog.PAGE_TEXT })
                                DropdownMenuItem({ Text("Go to page") }, leadingIcon = { Icon(Icons.Rounded.Numbers, null) },
                                    onClick = { menu = false; dialog = Dialog.GOTO })
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding()) {
                    busy?.let { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (index == null && viewFile != null && indexProgress in 0.001f..0.999f) {
                        LinearProgressIndicator(progress = { indexProgress }, modifier = Modifier.fillMaxWidth())
                    }
                    val sel = selection
                    when {
                        sel != null -> SelectionBar(
                            onCopy = {
                                clipboard.setText(AnnotatedString(index?.text(sel.page, sel.range).orEmpty()))
                                selection = null
                                scope.launch { snackbar.showSnackbar("Copied") }
                            },
                            onMarkup = { kind ->
                                val rects = index?.rects(sel.page, sel.range).orEmpty()
                                val quoted = index?.text(sel.page, sel.range)
                                selection = null
                                edit(kind.label) { doc -> Annotations.addMarkup(doc, sel.page, kind, rects, quoted) }
                            },
                            onClose = { selection = null },
                        )
                        mode == Mode.DRAW -> PenBar(penColour, { penColour = it }, penWidth, { penWidth = it })
                        searching && searched != null -> Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (hits.isEmpty()) "No matches for “$searched”" else "${current + 1} of ${hits.size} · page ${hits[current].page + 1}",
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                            )
                            if (hits.isNotEmpty()) {
                                IconButton(onClick = { showHits = true }) { Icon(Icons.AutoMirrored.Rounded.List, "All results") }
                                IconButton(onClick = { goToHit(current - 1) }) { Icon(Icons.Rounded.KeyboardArrowUp, "Previous") }
                                IconButton(onClick = { goToHit(current + 1) }) { Icon(Icons.Rounded.KeyboardArrowDown, "Next") }
                            }
                        }
                        chrome || searching -> if (pageCount > 0) PageScrubber(pager.currentPage, pageCount) { scope.launch { pager.scrollToPage(it) } }
                    }
                }
            }
        },
    ) { padding ->
        val p = pages
        when {
            locked == null || (locked == false && viewFile == null) -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            locked == true -> EmptyState(Icons.AutoMirrored.Rounded.TextSnippet, "Protected document", "Enter the password to open it.",
                Modifier.padding(padding).padding(top = 48.dp), action = "Enter password", onAction = { dialog = Dialog.PASSWORD })
            p == null || pageCount == 0 -> EmptyState(Icons.AutoMirrored.Rounded.TextSnippet, "This PDF can’t be opened", "It may be damaged.",
                Modifier.padding(padding).padding(top = 48.dp))
            else -> HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize().padding(padding),
                beyondViewportPageCount = 1,
                userScrollEnabled = mode == Mode.READ && selection == null,
                key = { it },
            ) { pageIndex ->
                val pageHits = remember(hits, pageIndex) { hits.withIndex().filter { it.value.page == pageIndex } }
                val sel = selection?.takeIf { it.page == pageIndex }
                PageView(
                    pages = p,
                    index = pageIndex,
                    mode = mode,
                    overlay = PageOverlay(
                        searchHits = pageHits.flatMap { it.value.rects },
                        activeHit = pageHits.firstOrNull { it.index == current }?.value?.rects.orEmpty(),
                        selection = sel?.let { index?.rects(pageIndex, it.range) }.orEmpty(),
                        strokes = if (strokePage == pageIndex) strokes.toList() else emptyList(),
                    ),
                    onTap = { if (selection != null) selection = null else chrome = !chrome },
                    onPlace = { u, v -> place = Triple(mode, pageIndex, u to v) },
                    onSelectStart = { u, v ->
                        val idx = index
                        val c = idx?.charNear(pageIndex, u, v)
                        if (idx == null) scope.launch { snackbar.showSnackbar("Still reading the text…") }
                        else if (c == null) scope.launch { snackbar.showSnackbar("No text here. Use “Make searchable (OCR)” for scanned pages.") }
                        else selection = Selection(pageIndex, c, idx.wordAround(pageIndex, c))
                    },
                    onSelectMove = { u, v ->
                        val s = selection
                        val c = index?.charNear(pageIndex, u, v)
                        if (s != null && s.page == pageIndex && c != null) {
                            val anchorWord = index!!.wordAround(pageIndex, s.anchor)
                            val word = index!!.wordAround(pageIndex, c)
                            selection = s.copy(range = minOf(anchorWord.first, word.first)..maxOf(anchorWord.last, word.last))
                        }
                    },
                    onStroke = { pts ->
                        if (strokePage != pageIndex) { strokes.clear(); strokePage = pageIndex }
                        strokes += InkStroke(penColour, if (penColour == PEN_COLOURS.last()) penWidth * 4 else penWidth, pts)
                    },
                    penColour = penColour,
                    penWidthPt = if (penColour == PEN_COLOURS.last()) penWidth * 4 else penWidth,
                )
            }
        }
    }

    if (showTools) ToolsSheet(onDismiss = { showTools = false }, onTool = ::onTool)
    if (showComments) CommentsSheet(
        comments,
        onDismiss = { showComments = false },
        onOpen = { c -> showComments = false; scope.launch { pager.scrollToPage(c.page) } },
        onDelete = { c ->
            scope.launch {
                runCatching { session.edit("Delete comment") { doc -> Annotations.delete(doc, c.page, c.index) } }
                    .onFailure { toast(it.message ?: "Could not delete") }
                loadComments()
            }
        },
    )
    if (showHits) HitsSheet(hits, searched, current, onDismiss = { showHits = false }) { i -> showHits = false; goToHit(i) }

    place?.let { (m, pageIndex, pos) ->
        TextEntryDialog(
            title = if (m == Mode.NOTE) "Sticky note" else "Add text",
            withSize = m == Mode.TEXT,
            onDismiss = { place = null },
            onDone = { text, size ->
                place = null
                mode = Mode.READ
                if (m == Mode.NOTE) edit("Add note", "Note added") { doc -> Annotations.addNote(doc, pageIndex, pos.first, pos.second, text) }
                else edit("Add text", "Text added") { doc -> marks.addText(doc, pageIndex, text, pos.first, pos.second, size, 0xFF212121.toInt()) }
            },
        )
    }

    when (dialog) {
        Dialog.PASSWORD -> PasswordDialog(wrongPassword, onDismiss = { dialog = null; if (locked == true) onBack() }) { pw ->
            scope.launch {
                session.password = pw
                val ok = runCatching { session.read { } }.exceptionOrNull() !is PasswordRequired
                if (ok) { wrongPassword = false; dialog = null; locked = false } else { session.password = null; wrongPassword = true }
            }
        }
        Dialog.PROTECT -> ProtectDialog(onDismiss = { dialog = null }) { open, owner, perms ->
            dialog = null
            scope.launch {
                runCatching { session.edit("Protect") { doc -> Protection.protect(doc, open, owner, perms) } }
                    .onSuccess { if (open.isNotEmpty()) session.password = open; snackbar.showSnackbar("Protected with AES-256") }
                    .onFailure { toast(it.message ?: "Could not protect the document") }
            }
        }
        Dialog.UNPROTECT -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Remove password?") },
            text = { Text("Anyone will be able to open, print and change the document.") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    scope.launch {
                        runCatching { session.edit("Remove password") { doc -> Protection.unprotect(doc) } }
                            .onSuccess { session.password = null; snackbar.showSnackbar("Password removed") }
                            .onFailure { toast(it.message ?: "Could not remove the password") }
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        Dialog.WATERMARK -> WatermarkDialog(onDismiss = { dialog = null }) { text, opacity ->
            dialog = null
            edit("Watermark", "Watermark added") { doc -> marks.watermark(doc, text, opacity) }
        }
        Dialog.HEADER_FOOTER -> HeaderFooterDialog(onDismiss = { dialog = null }) { template, pos, first ->
            dialog = null
            edit("Page numbers", "Added to every page") { doc -> marks.headerFooter(doc, template, pos, firstNumber = first) }
        }
        Dialog.OPTIMIZE -> OptimizeDialog(onDismiss = { dialog = null }) { level ->
            dialog = null
            scope.launch {
                busy = "Reducing file size…"
                val before = file.length()
                runCatching {
                    var n = 0
                    session.edit("Reduce file size") { doc -> n = Optimizer.reduce(doc, level) }
                    n
                }.onSuccess { n ->
                    val after = file.length()
                    snackbar.showSnackbar(if (n == 0) "Nothing to reduce: no large photos" else
                        "Recompressed $n image${if (n == 1) "" else "s"}: ${com.narl3yyy.pdfcs.ui.formatBytes(before)} → ${com.narl3yyy.pdfcs.ui.formatBytes(after)}")
                }.onFailure { toast(it.message ?: "Could not reduce the file") }
                busy = null
            }
        }
        Dialog.EXTRACT -> PagesDialog("Extract pages", "The pages are saved as a new PDF in your library.", pageCount, "Extract",
            initial = "${pager.currentPage + 1}", onDismiss = { dialog = null }) { list ->
            dialog = null
            newDocument("pages ${com.narl3yyy.pdfcs.core.PageRanges.format(list)}") { doc, target -> PageOps.extract(doc, list, target) }
        }
        Dialog.SPLIT -> SplitDialog(pageCount, onDismiss = { dialog = null }) { every ->
            dialog = null
            scope.launch {
                busy = "Splitting…"
                runCatching {
                    val parts = session.read { doc -> PageOps.split(doc, every, File(context.cacheDir, "split"), file.nameWithoutExtension) }
                    parts.forEach { part -> part.copyTo(AppGraph.documents.uniqueFile(part.nameWithoutExtension)); part.delete() }
                    AppGraph.documents.refresh()
                    parts.size
                }.onSuccess { snackbar.showSnackbar("Saved $it parts in your library") }.onFailure { toast(it.message ?: "Split failed") }
                busy = null
            }
        }
        Dialog.GOTO -> TextInputDialog(
            title = "Go to page", message = "1 – $pageCount", initial = "${pager.currentPage + 1}", confirm = "Go",
            onDismiss = { dialog = null },
            onConfirm = { t -> dialog = null; t.toIntOrNull()?.let { n -> scope.launch { pager.scrollToPage((n - 1).coerceIn(0, pageCount - 1)) } } },
        )
        Dialog.PAGE_TEXT -> {
            val text = index?.pages?.getOrNull(pager.currentPage)?.text.orEmpty()
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Text on page ${pager.currentPage + 1}") },
                text = {
                    SelectionContainer(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text(text.ifBlank { "No text on this page. Use “Make searchable (OCR)” to read it." })
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
                dismissButton = {
                    TextButton(enabled = text.isNotBlank(), onClick = { clipboard.setText(AnnotatedString(text)); dialog = null }) {
                        Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copy all")
                    }
                },
            )
        }
        null -> Unit
    }
}

@Composable
private fun SelectionBar(onCopy: () -> Unit, onMarkup: (Markup) -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        BarButton(Icons.Rounded.ContentCopy, "Copy", onCopy)
        BarButton(Icons.Rounded.FormatColorText, "Highlight") { onMarkup(Markup.HIGHLIGHT) }
        BarButton(Icons.Rounded.FormatUnderlined, "Underline") { onMarkup(Markup.UNDERLINE) }
        BarButton(Icons.Rounded.FormatStrikethrough, "Strike") { onMarkup(Markup.STRIKEOUT) }
        BarButton(Icons.Rounded.Close, "Clear", onClose)
    }
}

@Composable
private fun BarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(8.dp)) {
        Icon(icon, label)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PenBar(colour: Int, onColour: (Int) -> Unit, width: Float, onWidth: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PEN_COLOURS.forEach { c ->
                Box(
                    Modifier.size(32.dp).background(Color(c).copy(alpha = 1f), CircleShape)
                        .border(if (c == colour) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { onColour(c) },
                )
            }
            Text(if (colour == PEN_COLOURS.last()) "Highlighter" else "Pen", style = MaterialTheme.typography.labelLarge)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Width", Modifier.width(56.dp))
            Slider(width, onWidth, valueRange = 0.75f..8f, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PageScrubber(page: Int, count: Int, onSeek: (Int) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${(dragging?.roundToInt() ?: page) + 1} / $count", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(84.dp))
        if (count > 1) Slider(
            value = dragging ?: page.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { onSeek(it.roundToInt()) }; dragging = null },
            valueRange = 0f..(count - 1).toFloat(),
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HitsSheet(hits: List<SearchHit>, searched: String?, current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("${hits.size} results for “$searched” on ${hits.map { it.page }.distinct().size} pages",
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            itemsIndexed(hits) { i, hit ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(i) }
                        .background(if (i == current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text("p. ${hit.page + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(56.dp))
                    Text(hit.snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider()
            }
        }
        Spacer(Modifier.size(24.dp))
    }
}
