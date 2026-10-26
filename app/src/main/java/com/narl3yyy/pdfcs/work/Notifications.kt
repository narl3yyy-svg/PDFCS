package com.narl3yyy.pdfcs.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.narl3yyy.pdfcs.MainActivity
import com.narl3yyy.pdfcs.R

object Notifications {
    const val CHANNEL_PROGRESS = "ocr_progress"
    const val CHANNEL_DONE = "ocr_done"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Creating PDFs", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while pages are being recognised"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Finished PDFs", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when a searchable PDF is ready"
            },
        )
    }

    fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(context: Context, title: String, done: Int, total: Int) =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Creating “$title”")
            .setContentText(if (total > 0) "Reading page ${minOf(done + 1, total)} of $total" else "Preparing…")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .build()

    fun finished(context: Context, id: Int, title: String, success: Boolean, message: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(if (success) "“$title” is ready" else "Couldn’t create “$title”")
                .setContentText(message)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(context))
                .build(),
        )
    }
}
