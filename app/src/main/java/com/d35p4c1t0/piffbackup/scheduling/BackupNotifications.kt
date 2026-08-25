package com.d35p4c1t0.piffbackup.scheduling

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.d35p4c1t0.piffbackup.MainActivity
import com.d35p4c1t0.piffbackup.R

object BackupNotifications {
    const val NOTIFICATION_ID = 41001
    private const val CHANNEL_ID = "active-backup"

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.backup_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.backup_notification_channel_description)
            },
        )
    }

    fun active(context: Context, jobId: String, percentage: Int): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pause = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, BackupActionReceiver::class.java).apply {
                action = BackupActionReceiver.ACTION_PAUSE
                putExtra(BackupScheduler.JOB_ID_KEY, jobId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_backup_notification)
            .setContentTitle(context.getString(R.string.backup_notification_title))
            .setContentText(context.getString(R.string.backing_up_percentage_format, percentage))
            .setContentIntent(open)
            .setProgress(100, percentage, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, context.getString(R.string.pause_backup), pause)
            .build()
    }

    fun update(context: Context, jobId: String, percentage: Int) {
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, active(context, jobId, percentage))
    }
}
