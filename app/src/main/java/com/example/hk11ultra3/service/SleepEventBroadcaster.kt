package com.example.hk11ultra3.service

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Uyku olaylarını broadcast intent olarak yayınlar.
 *
 * Tasker: "Intent Received" profili ile yakalanabilir.
 * Macrodroid: "Intent Received" tetikleyicisi ile yakalanabilir.
 *
 * Yayınlanan intent'ler:
 *   com.example.hk11ultra3.SLEEP_DETECTED  — uykuya dalış tespit edildiğinde
 *   com.example.hk11ultra3.WAKE_DETECTED   — uyanma tespit edildiğinde
 *   com.example.hk11ultra3.SLEEP_UPDATED   — her sync'te güncel uyku verisi
 *
 * Her intent'in içerdiği extras:
 *   sleep_start_millis  — uyku başlangıcı (epoch ms)
 *   sleep_start_str     — "HH:mm" formatında
 *   wake_time_millis    — uyanma zamanı (epoch ms, sadece WAKE_DETECTED)
 *   wake_time_str       — "HH:mm" formatında
 *   total_sleep_minutes — toplam uyku (dk)
 *   deep_sleep_minutes  — derin uyku (dk)
 *   light_sleep_minutes — hafif uyku (dk)
 *   segment_count       — uyku segment sayısı
 *   reminder_time_millis— hatırlatıcı zamanı (epoch ms, sadece WAKE_DETECTED)
 *   reminder_time_str   — "HH:mm" formatında
 *   reminder_delay_min  — gecikme süresi (dk)
 */
object SleepEventBroadcaster {

    private const val TAG = "SleepEventBroadcaster"
    private const val PREFIX = "com.example.hk11ultra3"

    const val ACTION_SLEEP_DETECTED = "$PREFIX.SLEEP_DETECTED"
    const val ACTION_WAKE_DETECTED = "$PREFIX.WAKE_DETECTED"
    const val ACTION_SLEEP_UPDATED = "$PREFIX.SLEEP_UPDATED"

    /**
     * Uykuya dalış tespit edildi (ilk uyku segmenti alındı).
     */
    fun broadcastSleepDetected(
        context: Context,
        sleepStartMillis: Long,
        totalSleepMinutes: Int,
        deepSleepMinutes: Int,
        lightSleepMinutes: Int,
        segmentCount: Int
    ) {
        val intent = Intent(ACTION_SLEEP_DETECTED).apply {
            putExtra("sleep_start_millis", sleepStartMillis)
            putExtra("sleep_start_str", formatTime(sleepStartMillis))
            putExtra("total_sleep_minutes", totalSleepMinutes)
            putExtra("deep_sleep_minutes", deepSleepMinutes)
            putExtra("light_sleep_minutes", lightSleepMinutes)
            putExtra("segment_count", segmentCount)
        }
        send(context, intent, "SLEEP_DETECTED")
    }

    /**
     * Uyanma tespit edildi.
     */
    fun broadcastWakeDetected(
        context: Context,
        sleepStartMillis: Long,
        wakeTimeMillis: Long,
        totalSleepMinutes: Int,
        deepSleepMinutes: Int,
        lightSleepMinutes: Int,
        segmentCount: Int,
        reminderTimeMillis: Long,
        reminderDelayMin: Int
    ) {
        val intent = Intent(ACTION_WAKE_DETECTED).apply {
            putExtra("sleep_start_millis", sleepStartMillis)
            putExtra("sleep_start_str", formatTime(sleepStartMillis))
            putExtra("wake_time_millis", wakeTimeMillis)
            putExtra("wake_time_str", formatTime(wakeTimeMillis))
            putExtra("total_sleep_minutes", totalSleepMinutes)
            putExtra("deep_sleep_minutes", deepSleepMinutes)
            putExtra("light_sleep_minutes", lightSleepMinutes)
            putExtra("segment_count", segmentCount)
            putExtra("reminder_time_millis", reminderTimeMillis)
            putExtra("reminder_time_str", formatTime(reminderTimeMillis))
            putExtra("reminder_delay_min", reminderDelayMin)
        }
        send(context, intent, "WAKE_DETECTED")
    }

    /**
     * Uyku verisi güncellendi (her başarılı sync'te).
     */
    fun broadcastSleepUpdated(
        context: Context,
        sleepStartMillis: Long,
        wakeTimeMillis: Long?,
        totalSleepMinutes: Int,
        deepSleepMinutes: Int,
        lightSleepMinutes: Int,
        segmentCount: Int
    ) {
        val intent = Intent(ACTION_SLEEP_UPDATED).apply {
            putExtra("sleep_start_millis", sleepStartMillis)
            putExtra("sleep_start_str", formatTime(sleepStartMillis))
            if (wakeTimeMillis != null) {
                putExtra("wake_time_millis", wakeTimeMillis)
                putExtra("wake_time_str", formatTime(wakeTimeMillis))
            }
            putExtra("total_sleep_minutes", totalSleepMinutes)
            putExtra("deep_sleep_minutes", deepSleepMinutes)
            putExtra("light_sleep_minutes", lightSleepMinutes)
            putExtra("segment_count", segmentCount)
        }
        send(context, intent, "SLEEP_UPDATED")
    }

    private fun send(context: Context, intent: Intent, label: String) {
        intent.setPackage(context.packageName) // Explicit broadcast
        context.sendBroadcast(intent)
        Log.i(TAG, "Broadcast gönderildi: $label — ${intent.extras?.keySet()?.joinToString { "$it=${intent.extras?.get(it)}" }}")

        // Ayrıca implicit broadcast (Tasker/Macrodroid için)
        val implicit = Intent(intent.action).apply {
            intent.extras?.let { putExtras(it) }
        }
        context.sendBroadcast(implicit)
    }

    private fun formatTime(epochMillis: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
            .format(java.util.Date(epochMillis))
}
