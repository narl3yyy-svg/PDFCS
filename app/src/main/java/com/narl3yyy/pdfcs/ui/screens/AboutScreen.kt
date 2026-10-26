package com.narl3yyy.pdfcs.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.narl3yyy.pdfcs.BuildConfig

private const val REPO = "https://github.com/narl3yyy-svg/PDFCS"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Box(
                Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.DocumentScanner, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.height(16.dp))
            Text("PDFCS", style = MaterialTheme.typography.headlineMedium)
            Text("Version ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text(
                "Scan physical books with your phone camera and turn them into one clean, searchable PDF with real text. " +
                    "Everything — photos, OCR and your documents — stays on this device. No account, no ads, no tracking.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(24.dp))
            Text("Licence", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "PDFCS is free software: you can redistribute it and/or modify it under the terms of the " +
                    "GNU General Public License as published by the Free Software Foundation, either version 3 of " +
                    "the License, or (at your option) any later version. It is distributed in the hope that it will " +
                    "be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or " +
                    "FITNESS FOR A PARTICULAR PURPOSE.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = { open("https://www.gnu.org/licenses/gpl-3.0.html") }) { Text("Read the GPL-3.0") }
            Spacer(Modifier.height(24.dp))
            Text("Source code", style = MaterialTheme.typography.titleMedium)
            Text(REPO, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = { open(REPO) }) { Text("Open on GitHub") }
            Spacer(Modifier.height(24.dp))
            Text("Built with", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            listOf(
                "Tesseract OCR & tessdata_best models — Apache-2.0",
                "Tesseract4Android — Apache-2.0",
                "Leptonica — BSD-2-Clause",
                "PdfBox-Android — Apache-2.0",
                "Liberation fonts — SIL Open Font License 1.1",
                "AndroidX, Jetpack Compose & CameraX — Apache-2.0",
                "Page detection and clean-up ported from PdfCraft (storytold/pdfcraft) — MIT",
            ).forEach {
                Text("•  $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
            }
        }
    }
}
