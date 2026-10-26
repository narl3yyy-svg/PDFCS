package com.narl3yyy.pdfcs.core

import java.io.File
import java.io.IOException

/**
 * Writes go to a temporary sibling and are then renamed over the target, so a crash or a full
 * disk never leaves a half-written document behind (PdfCraft's "atomic save").
 */
object AtomicFile {
    inline fun write(target: File, block: (File) -> Unit) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.part")
        try {
            block(tmp)
            if (!tmp.exists() || tmp.length() == 0L) throw IOException("Nothing was written")
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
            }
        } finally {
            tmp.delete()
        }
    }
}
