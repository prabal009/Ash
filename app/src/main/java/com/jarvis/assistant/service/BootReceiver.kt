package com.jarvis.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.Settings
import com.jarvis.assistant.ui.MainActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = Settings(context)
        if (!settings.startOnBoot || !settings.switchedOn) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val channel = "ash_listening_reminders"
        manager.createNotificationChannel(NotificationChannel(
            channel, context.getString(R.string.ash_boot_channel), NotificationManager.IMPORTANCE_LOW,
        ))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(2, NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle(context.getString(R.string.ash_app_name))
            .setContentText(context.getString(R.string.ash_boot_reminder))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }
}
