package com.example.hk11ultra3.model

/**
 * Bir uyku kaydı segmenti.
 * Saatten gelen her bir sleepType + sleepDuration ikilisi.
 */
data class SleepRecord(
    val timestamp: Long,        // epoch millis
    val sleepType: Int,         // 1 = hafif uyku, 2 = derin uyku
    val sleepDuration: Int,     // dakika cinsinden süre
    val endTimestamp: Long      // bu segmentin bittiği zaman (timestamp + duration*dk)
)

/**
 * Günlük uyku verisi özeti.
 */
data class DailySleepData(
    val date: String,               // "2026-07-23"
    val records: List<SleepRecord>, // tüm segmentler
    val totalSleepMinutes: Int,     // toplam uyku süresi (dk)
    val deepSleepMinutes: Int,      // derin uyku süresi (dk)
    val lightSleepMinutes: Int,     // hafif uyku süresi (dk)
    val wakeTime: Long,             // uyanma zamanı (epoch millis) — son segmentin bitişi
    val sleepCount: Int             // kaç segment var
)

/**
 * Uygulama ayarları. SharedPreferences'ta saklanır.
 */
data class AppSettings(
    val reminderDelayMinutes: Int = 120,    // uyandıktan kaç dk sonra hatırlat
    val webhookUrl: String = "",            // webhook URL (boşsa gönderilmez)
    val targetMacAddress: String = "",      // bağlanacak saat MAC adresi
    val autoSyncOnBoot: Boolean = true      // açılışta otomatik senkronizasyon
)
