package com.example.hk11ultra3.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Periyodik BLE senkronizasyonunu yönetir.
 *
 * Strateji:
 *   - Gece 23:00 - 05:00 arası: her 30 dakikada bir (uyku sırasında batarya tasarrufu)
 *   - Sabah 05:00 - 12:00 arası: her 5 dakikada bir (uyanma penceresi, hızlı tespit)
 *   - Gündüz 12:00 - 23:00 arası: her 15 dakikada bir (gündüz yedek)
 *
 * Her sync'te, eğer bugün için uyanma tespit edilmişse periyodik sync durur.
 */
object SyncScheduler {

    private const val TAG = "SyncScheduler"
    private const val ACTION_PERIODIC_SYNC = "com.example.hk11ultra3.ACTION_PERIODIC_SYNC"

    // Dakika cinsinden aralıklar
    private const val INTERVAL_WAKE_WINDOW = 5L   // 05-12 arası
    private const val INTERVAL_DAY = 15L           // 12-23 arası
    private const val INTERVAL_NIGHT = 30L         // 23-05 arası

    /**
     * Bugün için uyanma tespit edilene kadar periyodik sync'i başlatır.
     * Uyanma zaten tespit edildiyse hiçbir şey yapmaz.
     */
    fun startPeriodicSync(context: Context) {
        val prefs = context.getSharedPreferences("hk11_settings", Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val lastWakeDate = prefs.getString("wake_detector_last_wake_date", "")

        if (lastWakeDate == today) {
            Log.d(TAG, "Bugün uyanma zaten tespit edilmiş, periyodik sync başlatılmıyor")
            return
        }

        val intervalMs = getCurrentIntervalMs()
        scheduleNext(context, intervalMs)
        Log.i(TAG, "Periyodik sync başladı — aralık: ${intervalMs / 60_000}dk")
    }

    /**
     * Periyodik sync'i tamamen durdurur (uyanma tespit edildiğinde çağrılır).
     */
    fun stopPeriodicSync(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, PeriodicSyncReceiver::class.java).apply {
            action = ACTION_PERIODIC_SYNC
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.i(TAG, "Periyodik sync durduruldu")
    }

    /**
     * Bir sonraki sync'i planlar.
     */
    fun scheduleNext(context: Context, delayMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, PeriodicSyncReceiver::class.java).apply {
            action = ACTION_PERIODIC_SYNC
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = System.currentTimeMillis() + delayMs

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        } catch (e: SecurityException) {
            // SCHEDULE_EXACT_ALARM izni yoksa, inexact dene
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
    }

    /**
     * Şu anki saate göre uygun sync aralığını döndürür.
     */
    private fun getCurrentIntervalMs(): Long {
        val hour = java.util.Calendar.getInstance()
            .get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            hour in 5..11 -> INTERVAL_WAKE_WINDOW * 60_000  // 5 dk
            hour in 12..22 -> INTERVAL_DAY * 60_000          // 15 dk
            else -> INTERVAL_NIGHT * 60_000                   // 30 dk
        }
    }

    /**
     * Şu anki saate göre uygun interval'i dakika olarak döndürür.
     */
    fun getCurrentIntervalMinutes(): Long {
        val hour = java.util.Calendar.getInstance()
            .get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            hour in 5..11 -> INTERVAL_WAKE_WINDOW
            hour in 12..22 -> INTERVAL_DAY
            else -> INTERVAL_NIGHT
        }
    }
}

/**
 * Periyodik sync alarmı tarafından tetiklenen BroadcastReceiver.
 */
class PeriodicSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.example.hk11ultra3.ACTION_PERIODIC_SYNC") return

        // Bugün uyanma tespit edilmiş mi kontrol et
        val prefs = context.getSharedPreferences("hk11_settings", Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val lastWakeDate = prefs.getString("wake_detector_last_wake_date", "")

        if (lastWakeDate == today) {
            Log.d("PeriodicSync", "Bugün uyanma zaten tespit edilmiş, sync atlanıyor")
            return
        }

        // Sync servisini başlat
        WatchSyncService.startSync(context)
    }
}
