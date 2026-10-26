package com.narl3yyy.pdfcs.ui.reader

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FormatColorText
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import com.narl3yyy.pdfcs.engine.Tool

fun toolIcon(tool: Tool): ImageVector = when (tool) {
    Tool.OCR -> Icons.Rounded.DocumentScanner
    Tool.ORGANIZE -> Icons.Rounded.ViewModule
    Tool.INSERT_FILE -> Icons.AutoMirrored.Rounded.InsertDriveFile
    Tool.EXTRACT -> Icons.Rounded.Upload
    Tool.SPLIT -> Icons.AutoMirrored.Rounded.CallSplit
    Tool.HIGHLIGHT -> Icons.Rounded.FormatColorText
    Tool.NOTE -> Icons.AutoMirrored.Rounded.StickyNote2
    Tool.DRAW -> Icons.Rounded.Brush
    Tool.COMMENTS -> Icons.Rounded.ChatBubbleOutline
    Tool.ADD_TEXT -> Icons.Rounded.TextFields
    Tool.FILL_FORM -> Icons.Rounded.EditNote
    Tool.WATERMARK -> Icons.Rounded.WaterDrop
    Tool.HEADER_FOOTER -> Icons.Rounded.Numbers
    Tool.PROPERTIES -> Icons.Rounded.Info
    Tool.PROTECT -> Icons.Rounded.Lock
    Tool.UNPROTECT -> Icons.Rounded.LockOpen
    Tool.EXPORT_TEXT -> Icons.AutoMirrored.Rounded.Notes
    Tool.EXPORT_WORD -> Icons.Rounded.Description
    Tool.EXPORT_IMAGES -> Icons.Rounded.Image
    Tool.OPTIMIZE -> Icons.Rounded.Compress
    Tool.SHARE -> Icons.Rounded.Share
    Tool.INFO -> Icons.Rounded.Analytics
}
