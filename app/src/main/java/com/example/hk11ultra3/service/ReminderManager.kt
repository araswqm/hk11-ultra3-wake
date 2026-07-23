package com.example.hk11ultra3.service

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * İlaç hatırlatıcısı yöneticisi.
 *
 * Uyanma zamanı + [delayMinutes] dakika sonrasına alarm kurar.
 * Hem telefona notification, hem de (BLE bağlıysa) saate bildirim gönderir.
 */
class ReminderManager(private val context: Context) {

    companion object {
        private const val TAG = "ReminderManager"
        private const val CHANNEL_ID = "medicine_reminder"
        private const val CHANNEL_NAME = "İlaç Hatırlatıcı"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_MEDICINE_REMINDER = "com.example.hk11ultra3.ACTION_MEDICINE_REMINDER"
    }

    init {
        createNotificationChannel()
    }

    /**
     * Uyanma zamanından [delayMinutes] dakika sonrasına hatırlatıcı kurar.
     *
     * @param wakeTimeMillis uyanma zamanı (epoch millis)
     * @param delayMinutes kaç dakika sonra (ayarlanabilir)
     * @return hatırlatıcının kurulacağı epoch millis
     */
    fun scheduleReminder(wakeTimeMillis: Long, delayMinutes: Int): Long {
        val reminderTime = wakeTimeMillis + (delayMinutes * 60_000L)

        // Geçmiş zamansa hemen şimdi tetikle
        val effectiveTime = if (reminderTime <= System.currentTimeMillis()) {
            Log.w(TAG, "Hatırlatıcı zamanı geçmiş, hemen tetikleniyor")
            System.currentTimeMillis() + 5000  // 5 saniye sonra
        } else {
            reminderTime
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, MedicineReminderReceiver::class.java).apply {
            action = ACTION_MEDICINE_REMINDER
            putExtra("wake_time", wakeTimeMillis)
            putExtra("reminder_time", effectiveTime)
            putExtra("delay_minutes", delayMinutes)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Exact alarm kur
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                effectiveTime,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                effectiveTime,
                pendingIntent
            )
        }

        val timeStr = WakeDetector.formatTime(effectiveTime)
        Log.i(TAG, "Hatırlatıcı kuruldu: $timeStr (${delayMinutes}dk sonra)")

        return effectiveTime
    }

    /**
     * Telefona bildirim gönderir (hemen).
     */
    fun showLocalNotification(title: String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Bildirim izni yok")
                return
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "İlaç hatırlatıcı bildirimleri"
                enableVibration(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}

/**
 * Alarm tetiklendiğinde çağrılan BroadcastReceiver.
 */
class MedicineReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderManager.ACTION_MEDICINE_REMINDER) return

        Log.i("MedicineReminder", "Hatırlatıcı tetiklendi!")

        val reminderManager = ReminderManager(context)
        reminderManager.showLocalNotification(
            "💊 İlaç Zamanı!",
            "Uyandıktan ${intent.getIntExtra("delay_minutes", 120)} dakika sonra. İlacını almayı unutma!"
        )

        // WatchSyncService'i başlatıp saate de bildirim göndermeyi dene
        val serviceIntent = Intent(context, WatchSyncService::class.java).apply {
            action = WatchSyncService.ACTION_SEND_REMINDER
            putExtra("title", "İlaç Zamanı!")
            putExtra("message", "İlacını almayı unutma!")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
