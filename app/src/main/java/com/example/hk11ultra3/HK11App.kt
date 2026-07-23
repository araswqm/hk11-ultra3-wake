package com.example.hk11ultra3

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class HK11App : Application() {
    override fun onCreate() {
        super.onCreate()

        // Notification kanallarını oluştur
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channels = listOf(
                NotificationChannel(
                    "watch_sync",
                    "Saat Senkronizasyonu",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Arka plan saat bağlantısı"
                },
                NotificationChannel(
                    "medicine_reminder",
                    "İlaç Hatırlatıcı",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "İlaç zamanı bildirimleri"
                    enableVibration(true)
                }
            )
            val manager = getSystemService(NotificationManager::class.java)
            channels.forEach { manager.createNotificationChannel(it) }
        }
    }
}
