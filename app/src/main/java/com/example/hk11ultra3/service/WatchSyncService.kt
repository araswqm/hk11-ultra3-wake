package com.example.hk11ultra3.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.hk11ultra3.MainActivity
import com.example.hk11ultra3.ble.BleManager
import com.example.hk11ultra3.ble.SleepDataParser
import com.example.hk11ultra3.model.AppSettings
import com.example.hk11ultra3.model.SleepRecord
import kotlinx.coroutines.*

/**
 * Arka plan servisi — tam otomatik BLE senkronizasyonu, uyanma tespiti,
 * webhook ve ilaç hatırlatıcısı.
 *
 * ÇALIŞMA ŞEKLİ:
 *   1. startSync() çağrılır (manuel buton, boot, veya periyodik alarm ile)
 *   2. Saate BLE bağlantısı kurar → handshake → uyku verisi talep eder
 *   3. Gelen uyku kayıtlarını parse eder
 *   4. Uyanma tespit ederse:
 *      → Webhook gönderir (anında)
 *      → İlaç hatırlatıcısı kurar (uyanma + X dakika)
 *      → Periyodik sync'i durdurur (bugünlük)
 *   5. Uyanma tespit EDEMEZSE:
 *      → Bir sonraki periyodik sync'i planlar
 *      → Servisi durdurur (batarya tasarrufu)
 */
class WatchSyncService : Service() {

    companion object {
        private const val TAG = "WatchSyncService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "watch_sync"

        const val ACTION_START_SYNC = "com.example.hk11ultra3.ACTION_START_SYNC"
        const val ACTION_START_PERIODIC = "com.example.hk11ultra3.ACTION_START_PERIODIC"
        const val ACTION_SEND_REMINDER = "com.example.hk11ultra3.ACTION_SEND_REMINDER"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"

        fun startSync(context: Context) {
            val intent = Intent(context, WatchSyncService::class.java).apply {
                action = ACTION_START_SYNC
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startPeriodicSync(context: Context) {
            val intent = Intent(context, WatchSyncService::class.java).apply {
                action = ACTION_START_PERIODIC
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun sendReminder(context: Context, title: String, message: String) {
            val intent = Intent(context, WatchSyncService::class.java).apply {
                action = ACTION_SEND_REMINDER
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MESSAGE, message)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var bleManager: BleManager
    private lateinit var reminderManager: ReminderManager
    private lateinit var prefs: SharedPreferences
    private var isSyncing = false

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager(this)
        reminderManager = ReminderManager(this)
        prefs = getSharedPreferences("hk11_settings", Context.MODE_PRIVATE)
        startForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SYNC -> handleStartSync(isPeriodic = false)
            ACTION_START_PERIODIC -> handleStartSync(isPeriodic = true)
            ACTION_SEND_REMINDER -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Hatırlatıcı"
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: ""
                handleSendReminder(title, message)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        bleManager.disconnect()
        super.onDestroy()
    }

    // ── Senkronizasyon akışı ──────────────────────────────────────────

    private fun handleStartSync(isPeriodic: Boolean) {
        if (isSyncing) {
            Log.d(TAG, "Zaten senkronize ediliyor, tekrar atlanıyor")
            return
        }
        isSyncing = true

        val settings = loadSettings()
        if (settings.targetMacAddress.isBlank()) {
            Log.w(TAG, "Hedef MAC adresi ayarlanmamış")
            isSyncing = false
            updateNotification("⚠ MAC adresi gerekli")
            stopSelf()
            return
        }

        // Bugün uyanma zaten tespit edilmiş mi?
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val lastWakeDate = prefs.getString("wake_detector_last_wake_date", "")
        if (lastWakeDate == today && isPeriodic) {
            Log.i(TAG, "Bugün uyanma zaten tespit edilmiş, periyodik sync atlanıyor")
            isSyncing = false
            updateNotification("✅ Bugünkü uyanma tespit edildi")
            stopSelf()
            return
        }

        val intervalMin = if (isPeriodic) SyncScheduler.getCurrentIntervalMinutes() else 0
        updateNotification("🔄 Saate bağlanılıyor... (${if (isPeriodic) "her ${intervalMin}dk" else "manuel"})")

        bleManager.callback = object : BleManager.Callback {
            override fun onConnected(mac: String) {
                Log.i(TAG, "Bağlandı: $mac")
                updateNotification("🔗 Bağlandı, uyku verisi alınıyor...")
                serviceScope.launch {
                    try {
                        bleManager.handshake()
                        delay(500)
                        bleManager.requestSleepData()
                    } catch (e: Exception) {
                        Log.e(TAG, "Sync hatası", e)
                        finishSync(settings, wakeDetected = false, isPeriodic = isPeriodic)
                    }
                }
            }

            override fun onDisconnected() {
                Log.i(TAG, "Bağlantı kesildi")
                finishSync(settings, wakeDetected = false, isPeriodic = isPeriodic)
            }

            override fun onSleepRecord(record: SleepRecord) {
                Log.d(TAG, "Uyku kaydı: type=${record.sleepType} duration=${record.sleepDuration}dk")
            }

            override fun onSyncComplete(sleepRecords: List<SleepRecord>) {
                Log.i(TAG, "Sync tamamlandı: ${sleepRecords.size} kayıt")
                val wakeDetected = processSleepData(sleepRecords, settings)
                finishSync(settings, wakeDetected, isPeriodic)
            }

            override fun onError(message: String) {
                Log.e(TAG, "Hata: $message")
                finishSync(settings, wakeDetected = false, isPeriodic = isPeriodic)
            }
        }

        serviceScope.launch {
            try {
                withTimeout(30_000L) {
                    bleManager.scanAndConnect(settings.targetMacAddress)
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Bağlantı zaman aşımı")
                finishSync(settings, wakeDetected = false, isPeriodic = isPeriodic)
            } catch (e: Exception) {
                Log.e(TAG, "Bağlantı hatası", e)
                finishSync(settings, wakeDetected = false, isPeriodic = isPeriodic)
            }
        }
    }

    /**
     * Gelen uyku verilerini isler.
     * Tum kayitlari kabul eder (sadece bugun degil), en son uyku seansi uzerinden calisir.
     * @return true eger uyanma tespit edildiyse
     */
    private fun processSleepData(records: List<SleepRecord>, settings: AppSettings): Boolean {
        if (records.isEmpty()) {
            Log.i(TAG, "Uyku kaydi alinamadi")
            // Save empty status so UI knows sync happened but no data
            prefs.edit().putLong("summary_sync_time", System.currentTimeMillis()).apply()
            return false
        }

        // Group records by sleep session: a session is records within 6 hours of each other
        val sessions = groupIntoSessions(records.sortedBy { it.timestamp })
        val latestSession = sessions.lastOrNull() ?: return false

        Log.i(TAG, "${records.size} uyku kaydi, ${sessions.size} uyku seansi bulundu")
        Log.i(TAG, "En son seans: ${latestSession.size} segment")

        // Save summary for the latest session (for UI)
        saveSleepSummary(latestSession)

        val sleepStart = latestSession.minOf { it.timestamp }
        val totalMin = latestSession.sumOf { it.sleepDuration }
        val deepMin = latestSession.filter { it.sleepType == 2 }.sumOf { it.sleepDuration }
        val lightMin = latestSession.filter { it.sleepType == 1 }.sumOf { it.sleepDuration }
        val segments = latestSession.size
        val possibleWake = latestSession.maxOf { it.endTimestamp }

        // Broadcast update
        SleepEventBroadcaster.broadcastSleepUpdated(
            context = this,
            sleepStartMillis = sleepStart,
            wakeTimeMillis = possibleWake,
            totalSleepMinutes = totalMin,
            deepSleepMinutes = deepMin,
            lightSleepMinutes = lightMin,
            segmentCount = segments
        )

        // Wake detection: use latest session
        val wakeTime = WakeDetector.detectWakeUp(latestSession, prefs)
            ?: return false  // zaten tespit edilmis veya veri yetersiz

        // ═══════════════════════════════════════════════════════════
        // UYANMA TESPİT EDİLDİ!
        // ═══════════════════════════════════════════════════════════

        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "🌅 UYANMA TESPİT EDİLDİ!")
        Log.i(TAG, "   Zaman: ${WakeDetector.formatTime(wakeTime)}")
        Log.i(TAG, "═══════════════════════════════════════")

        // 1. Hatırlatıcıyı kur
        val delayMinutes = settings.reminderDelayMinutes
        val reminderTime = reminderManager.scheduleReminder(wakeTime, delayMinutes)
        val reminderTimeStr = WakeDetector.formatTime(reminderTime)

        // 2. Toplam uyku suresi
        val totalSleepMin = latestSession.sumOf { it.sleepDuration }
        val sleepHours = totalSleepMin / 60
        val sleepMins = totalSleepMin % 60

        // 3. Foreground notification'ı güncelle
        updateNotification("🌅 Uyandın! İlaç: $reminderTimeStr")

        // 4. Webhook gönder (anında, ayrı bir coroutine'de)
        serviceScope.launch {
            if (settings.webhookUrl.isNotBlank()) {
                val sent = WebhookSender.send(
                    url = settings.webhookUrl,
                    wakeTime = wakeTime,
                    reminderTime = reminderTime,
                    totalSleepMinutes = totalSleepMin
                )
                if (sent) {
                    Log.i(TAG, "✅ Webhook gönderildi")
                } else {
                    Log.e(TAG, "❌ Webhook başarısız")
                }
            }
        }

        // 5. Periyodik sync'i durdur (bugünlük)
        SyncScheduler.stopPeriodicSync(this)

        // 6. Telefona bildirim
        reminderManager.showLocalNotification(
            "🌅 Uyandın! (${sleepHours}s ${sleepMins}dk uyku)",
            "İlaç hatırlatıcı: $reminderTimeStr (${delayMinutes}dk sonra)"
        )

        // 7. Tasker/Macrodroid broadcast
        SleepEventBroadcaster.broadcastWakeDetected(
            context = this,
            sleepStartMillis = sleepStart,
            wakeTimeMillis = wakeTime,
            totalSleepMinutes = totalSleepMin,
            deepSleepMinutes = deepMin,
            lightSleepMinutes = lightMin,
            segmentCount = segments,
            reminderTimeMillis = reminderTime,
            reminderDelayMin = delayMinutes
        )

        return true
    }

    /**
     * Uyku ozetini SharedPreferences'a kaydeder (UI'da gosterilecek).
     */
    private fun saveSleepSummary(records: List<SleepRecord>) {
        if (records.isEmpty()) return

        val wakeTime = records.maxOf { it.endTimestamp }
        val sleepStartTime = records.minOf { it.timestamp }
        val totalMin = records.sumOf { it.sleepDuration }
        val deepMin = records.filter { it.sleepType == 2 }.sumOf { it.sleepDuration }
        val lightMin = records.filter { it.sleepType == 1 }.sumOf { it.sleepDuration }
        val sessionDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(sleepStartTime))

        prefs.edit()
            .putLong("summary_sleep_start", sleepStartTime)
            .putLong("summary_wake_time", wakeTime)
            .putInt("summary_total_min", totalMin)
            .putInt("summary_deep_min", deepMin)
            .putInt("summary_light_min", lightMin)
            .putInt("summary_segment_count", records.size)
            .putString("summary_date", sessionDate)
            .putLong("summary_sync_time", System.currentTimeMillis())
            .apply()

        Log.d(TAG, "Uyku ozeti kaydedildi: $sessionDate - ${totalMin}dk (derin:${deepMin}dk, hafif:${lightMin}dk)")
    }

    /**
     * Uyku kayitlarini seanslara boler.
     * 6 saatten fazla bosluk varsa yeni seans baslatir.
     */
    private fun groupIntoSessions(sorted: List<SleepRecord>): List<List<SleepRecord>> {
        if (sorted.isEmpty()) return emptyList()
        val sessions = mutableListOf<MutableList<SleepRecord>>()
        var current = mutableListOf(sorted[0])
        for (i in 1 until sorted.size) {
            val gap = sorted[i].timestamp - sorted[i - 1].endTimestamp
            if (gap > 6 * 60 * 60 * 1000) {
                sessions.add(current)
                current = mutableListOf()
            }
            current.add(sorted[i])
        }
        sessions.add(current)
        return sessions
    }

    /**
     * Sync bittikten sonra yapılacaklar.
     */
    private fun finishSync(
        settings: AppSettings,
        wakeDetected: Boolean,
        isPeriodic: Boolean
    ) {
        isSyncing = false
        bleManager.disconnect()

        if (wakeDetected) {
            // Uyanma tespit edildi → servisi kapat, bugünlük iş tamam
            Log.i(TAG, "Uyanma tespit edildi, servis kapatılıyor")
            stopSelf()
        } else if (isPeriodic) {
            // Periyodik modda → bir sonraki sync'i planla, servisi kapat
            val intervalMs = SyncScheduler.getCurrentIntervalMinutes() * 60_000
            SyncScheduler.scheduleNext(this, intervalMs)
            Log.i(TAG, "Sonraki periyodik sync: ${intervalMs / 60_000}dk sonra")
            updateNotification("⏳ Sonraki kontrol: ~${intervalMs / 60_000}dk sonra")
            stopSelf()
        } else {
            // Manuel modda → direkt kapat
            updateNotification("✅ Sync tamam")
            stopSelf()
        }
    }

    // ── Hatırlatıcı ───────────────────────────────────────────────────

    private fun handleSendReminder(title: String, message: String) {
        val settings = loadSettings()
        updateNotification("💊 Saate bildirim gönderiliyor...")

        serviceScope.launch {
            try {
                withTimeout(15_000L) {
                    if (!bleManager.isConnected()) {
                        bleManager.scanAndConnect(settings.targetMacAddress)
                        bleManager.handshake()
                        delay(300)
                    }
                    bleManager.sendNotificationToWatch(title, message)
                }
                updateNotification("✅ İlaç bildirimi saate gönderildi")
            } catch (e: Exception) {
                Log.e(TAG, "Saate bildirim gönderilemedi (saat uzakta olabilir)", e)
            }
            delay(2000)
            stopSelf()
        }
    }

    // ── Yardımcılar ───────────────────────────────────────────────────

    private fun loadSettings(): AppSettings {
        return AppSettings(
            reminderDelayMinutes = prefs.getInt("reminder_delay_minutes", 120),
            webhookUrl = prefs.getString("webhook_url", "") ?: "",
            targetMacAddress = prefs.getString("target_mac", "") ?: "",
            autoSyncOnBoot = prefs.getBoolean("auto_sync_on_boot", true)
        )
    }

    private fun updateNotification(text: String) {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("HK11 Wake")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun startForeground() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("HK11 Wake")
            .setContentText("⏳ Bekleniyor...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "Saat Senkronizasyonu",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        startForeground(NOTIFICATION_ID, notification)
    }
}
