package com.narl3yyy.pdfcs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.narl3yyy.pdfcs.ui.screens.AboutScreen
import com.narl3yyy.pdfcs.ui.screens.CameraScreen
import com.narl3yyy.pdfcs.ui.screens.LibraryScreen
import com.narl3yyy.pdfcs.ui.screens.PageEditorScreen
import com.narl3yyy.pdfcs.ui.screens.ReviewScreen
import com.narl3yyy.pdfcs.ui.screens.SettingsScreen
import com.narl3yyy.pdfcs.ui.inspect.FormScreen
import com.narl3yyy.pdfcs.ui.inspect.InfoScreen
import com.narl3yyy.pdfcs.ui.organize.OrganizeScreen
import com.narl3yyy.pdfcs.ui.reader.ReaderScreen
import com.narl3yyy.pdfcs.reader.PdfPages
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import android.widget.Toast
import com.narl3yyy.pdfcs.ui.theme.PdfcsTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Lets the camera screen use the volume keys as a shutter (handy when holding a book open). */
object VolumeShutter {
    @Volatile var active = false
    private val _presses = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val presses: SharedFlow<Unit> = _presses.asSharedFlow()
    fun press() = _presses.tryEmit(Unit)
}

class MainActivity : ComponentActivity() {
    /** A PDF another app asked us to open; consumed by the library screen. */
    private var incomingPdf by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) incomingPdf = pdfFrom(intent)

        setContent {
            PdfcsTheme {
                val nav = rememberNavController()
                val enter = slideInHorizontally { it / 6 } + fadeIn()
                val exit = fadeOut()
                val popEnter = fadeIn()
                val popExit = slideOutHorizontally { it / 6 } + fadeOut()

                LaunchedEffect(Unit) { AppGraph.documents.refresh() }
                val pathArg = navArgument("path") { type = NavType.StringType; defaultValue = "" }

                NavHost(
                    navController = nav,
                    startDestination = "library",
                    enterTransition = { enter },
                    exitTransition = { exit },
                    popEnterTransition = { popEnter },
                    popExitTransition = { popExit },
                ) {
                    composable("library") {
                        LibraryScreen(
                            incomingPdf = incomingPdf,
                            onIncomingHandled = { incomingPdf = null },
                            onScan = { nav.navigate("camera") },
                            onReview = { nav.navigate("review") },
                            onOpen = { file -> nav.navigate("viewer?path=${Uri.encode(file.path)}") },
                            onOrganize = { file -> nav.navigate("organize?path=${Uri.encode(file.path)}") },
                            onInfo = { file -> nav.navigate("info?path=${Uri.encode(file.path)}") },
                            onSettings = { nav.navigate("settings") },
                            onAbout = { nav.navigate("about") },
                        )
                    }
                    composable("camera") {
                        CameraScreen(
                            onBack = { nav.popBackStack() },
                            onDone = {
                                nav.navigate("review") { popUpTo("library") }
                            },
                        )
                    }
                    composable("review") {
                        ReviewScreen(
                            onBack = { nav.popBackStack("library", inclusive = false) },
                            onAddPages = { nav.navigate("camera") },
                            onEditPage = { id -> nav.navigate("edit/$id") },
                            onExported = { nav.popBackStack("library", inclusive = false) },
                        )
                    }
                    composable("edit/{id}", listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                        PageEditorScreen(
                            pageId = entry.arguments?.getString("id").orEmpty(),
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("viewer?path={path}", listOf(pathArg)) { entry ->
                        val scope = rememberCoroutineScope()
                        ReaderScreen(
                            path = entry.arguments?.getString("path").orEmpty(),
                            onBack = { nav.popBackStack() },
                            onOrganize = { nav.navigate("organize?path=${Uri.encode(it.path)}") },
                            onInfo = { nav.navigate("info?path=${Uri.encode(it.path)}") },
                            onFillForm = { nav.navigate("form?path=${Uri.encode(it.path)}") },
                            onMakeSearchable = { f ->
                                scope.launch {
                                    runCatching { PdfPages.importIntoSession(f, AppGraph.session) { _, _ -> } }
                                        .onSuccess { nav.navigate("review") }
                                        .onFailure { Toast.makeText(this@MainActivity, it.message ?: "Could not read that PDF", Toast.LENGTH_LONG).show() }
                                }
                            },
                        )
                    }
                    composable("organize?path={path}", listOf(pathArg)) { entry ->
                        OrganizeScreen(entry.arguments?.getString("path").orEmpty(), onBack = { nav.popBackStack() })
                    }
                    composable("info?path={path}", listOf(pathArg)) { entry ->
                        InfoScreen(entry.arguments?.getString("path").orEmpty(), onBack = { nav.popBackStack() })
                    }
                    composable("form?path={path}", listOf(pathArg)) { entry ->
                        FormScreen(entry.arguments?.getString("path").orEmpty(), onBack = { nav.popBackStack() })
                    }
                    composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }, onAbout = { nav.navigate("about") }) }
                    composable("about") { AboutScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pdfFrom(intent)?.let { incomingPdf = it }
    }

    private fun isShutterKey(keyCode: Int) =
        (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            VolumeShutter.active && AppGraph.settings.value.volumeShutter

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isShutterKey(keyCode)) return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) VolumeShutter.press()
        return true
    }

    // Swallow the release too, so the volume panel never appears while shooting.
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        isShutterKey(keyCode) || super.onKeyUp(keyCode, event)

    private fun pdfFrom(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }
}
