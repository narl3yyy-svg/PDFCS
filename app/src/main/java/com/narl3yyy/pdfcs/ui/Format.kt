package com.narl3yyy.pdfcs.ui

import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.US, "%d KB", bytes / (1L shl 10))
    else -> "$bytes B"
}

fun formatDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

fun defaultScanTitle(): String =
    "Scan " + java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date())
