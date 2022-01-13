package me.lucky.wasted

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class AppNotificationManager(private val ctx: Context) {
    companion object {
        const val CHANNEL_DEFAULT_ID = "default"
    }

    private val manager = ctx.getSystemService(NotificationManager::class.java)

    fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DEFAULT_ID,
                ctx.getString(R.string.notification_channel_default_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }
}