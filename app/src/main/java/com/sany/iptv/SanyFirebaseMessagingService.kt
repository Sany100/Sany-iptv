package com.sany.iptv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class SanyFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        com.google.firebase.messaging.FirebaseMessaging.getInstance()
            .subscribeToTopic("sany_iptv_all")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title
            ?: message.data["title"]
            ?: "SANY IPTV Update"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: "নতুন আপডেট এসেছে। আপডেট দেখতে এখানে চাপ দিন।"
        showUpdateNotification(title, body)
    }

    private fun showUpdateNotification(title: String, body: String) {
        val channelId = "sany_updates"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "SANY IPTV Updates", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Important SANY IPTV update announcements" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("force_update_check", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 904, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(this)
                .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
        } catch (security: SecurityException) {
            android.util.Log.w("SANY-FCM", "Notification permission is not granted", security)
        }
    }
}
