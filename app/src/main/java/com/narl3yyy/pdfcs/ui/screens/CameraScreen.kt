package com.narl3yyy.pdfcs.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.GridOff
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.narl3yyy.pdfcs.AppGraph
import com.narl3yyy.pdfcs.VolumeShutter
import com.narl3yyy.pdfcs.ui.components.PageThumbnail
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Flash(val mode: Int) {
    OFF(ImageCapture.FLASH_MODE_OFF), AUTO(ImageCapture.FLASH_MODE_AUTO), ON(ImageCapture.FLASH_MODE_ON)
}

@Composable
fun CameraScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraContent(onBack, onDone)
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.NoPhotography, null, tint = Color.White, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(16.dp))
                Text(
                    "PDFCS needs the camera to photograph your pages. Photos stay on this phone.",
                    color = Color.White, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { request.launch(Manifest.permission.CAMERA) }) {
                    Text(if (asked) "Try again" else "Allow camera")
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onBack, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("Back") }
            }
        }
    }
}

@Composable
private fun CameraContent(onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val session = AppGraph.session
    val settings by AppGraph.settings.state.collectAsState()
    val pages by session.pages.collectAsState()

    var flash by remember { mutableStateOf(Flash.OFF) }
    var torch by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(true) }
    var auto by remember { mutableStateOf(false) }
    var saving by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val flashAnim = remember { Animatable(0f) }
    val autoProgress = remember { Animatable(0f) }
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .build()
            imageCaptureResolutionSelector = selector
            previewResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
        }
    }
    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        VolumeShutter.active = true
        view.keepScreenOn = true
        onDispose {
            VolumeShutter.active = false
            view.keepScreenOn = false
            controller.unbind()
            sound.release()
        }
    }
    LaunchedEffect(flash) { controller.imageCaptureFlashMode = flash.mode }
    LaunchedEffect(torch) { controller.enableTorch(torch) }

    fun capture() {
        val file = session.newImageFile()
        saving++
        if (settings.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        scope.launch {
            flashAnim.snapTo(0.85f)
            flashAnim.animateTo(0f, tween(260))
        }
        controller.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    saving--
                    session.addPage(file, settings.defaultLook, findEdges = settings.autoCrop)
                }

                override fun onError(exception: ImageCaptureException) {
                    saving--
                    file.delete()
                    error = exception.message ?: "Capture failed"
                }
            },
        )
    }

    LaunchedEffect(Unit) { VolumeShutter.presses.collect { capture() } }

    // Hands-free mode: shoot every few seconds while you turn the pages.
    LaunchedEffect(auto, settings.autoCaptureSeconds) {
        if (!auto) { autoProgress.snapTo(0f); return@LaunchedEffect }
        while (true) {
            autoProgress.snapTo(0f)
            autoProgress.animateTo(1f, tween(settings.autoCaptureSeconds * 1000, easing = LinearEasing))
            capture()
            delay(150)
        }
    }

    LaunchedEffect(error) {
        if (error != null) { delay(3000); error = null }
    }

    Box(Modifier.fillMaxSize()) {
        // 4:3 preview matching the captured photo, so what you frame is what you get.
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(3f / 4f),
        ) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        this.controller = controller
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (grid) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = Color.White.copy(alpha = 0.35f)
                    for (i in 1..2) {
                        drawLine(c, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.5f)
                        drawLine(c, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.5f)
                    }
                }
            }
            Box(Modifier.fillMaxSize().alpha(flashAnim.value).background(Color.White))
        }

        // Top controls
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CamIcon(Icons.Rounded.Close, "Close", onClick = onBack)
            Spacer(Modifier.weight(1f))
            CamIcon(
                when (flash) { Flash.OFF -> Icons.Rounded.FlashOff; Flash.AUTO -> Icons.Rounded.FlashAuto; Flash.ON -> Icons.Rounded.FlashOn },
                "Flash",
            ) { flash = Flash.entries[(flash.ordinal + 1) % Flash.entries.size] }
            CamIcon(if (torch) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff, "Light", active = torch) { torch = !torch }
            CamIcon(if (grid) Icons.Rounded.GridOn else Icons.Rounded.GridOff, "Grid") { grid = !grid }
            CamIcon(Icons.Rounded.Timer, "Auto capture", active = auto) { auto = !auto }
        }

        // Hints / errors
        val hint = when {
            error != null -> error
            auto -> "Auto capture every ${settings.autoCaptureSeconds}s — turn the page between shots"
            pages.isEmpty() -> "Fill the frame with the page, keep it flat and well lit" +
                if (settings.volumeShutter) " · volume keys take photos" else ""
            else -> null
        }
        if (hint != null) {
            Text(
                hint,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp, start = 24.dp, end = 24.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        // Bottom controls
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.35f))
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Last page + count
            Box(Modifier.size(60.dp).clickable(enabled = pages.isNotEmpty(), onClick = onDone)) {
                pages.lastOrNull()?.let { PageThumbnail(session.dir, it, Modifier.fillMaxSize(), maxSide = 240) }
                if (pages.isNotEmpty()) {
                    Text(
                        "${pages.size}",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(0.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }

            // Shutter
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(84.dp)) {
                val ring = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color.White, style = Stroke(5.dp.toPx()))
                    if (auto) {
                        drawArc(ring, -90f, 360f * autoProgress.value, false, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
                Box(
                    Modifier
                        .size(66.dp)
                        .background(if (auto) ring else Color.White, CircleShape)
                        .clickable { if (auto) auto = false else capture() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (auto) Icon(Icons.Rounded.Pause, "Stop auto capture", tint = Color.White)
                }
            }

            // Done
            Button(
                onClick = onDone,
                enabled = pages.isNotEmpty() || saving > 0,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Done")
            }
        }
    }
}

@Composable
private fun CamIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (active) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.4f),
            contentColor = Color.White,
        ),
        modifier = Modifier.padding(horizontal = 2.dp).border(0.dp, Color.Transparent, CircleShape),
    ) {
        Icon(icon, label)
    }
}
