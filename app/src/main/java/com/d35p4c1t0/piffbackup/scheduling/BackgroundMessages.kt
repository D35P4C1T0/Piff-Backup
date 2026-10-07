package com.d35p4c1t0.piffbackup.scheduling

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.d35p4c1t0.piffbackup.MainActivity
import com.d35p4c1t0.piffbackup.R

class BackgroundMessages(private val context: Context) {
    fun attention() {
        val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "active-backup")
            .setSmallIcon(R.drawable.ic_backup_notification).setContentTitle(context.getString(R.string.needs_attention))
            .setContentText(context.getString(R.string.scheduled_needs_attention))
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(41003, notification) }
    }
}
