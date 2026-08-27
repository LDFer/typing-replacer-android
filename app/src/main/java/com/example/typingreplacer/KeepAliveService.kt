package com.example.typingreplacer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 前台保活服务：给“打字替换”一个常驻通知，降低被系统后台清理的概率。
 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "后台替换保活",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("打字替换")
            .setContentText("正在后台运行，继续替换文字")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL_ID = "typing_replacer_keepalive"
        const val NOTIFICATION_ID = 2
    }
}
