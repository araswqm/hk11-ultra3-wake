package com.example.hk11ultra3.service

import android.content.SharedPreferences
import android.util.Log
import com.example.hk11ultra3.model.SleepRecord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Uyku kayıtlarından uyanma zamanını tespit eder.
 *
 * Algoritma:
 *   1. Tüm sleep segmentlerini al
 *   2. Son segmentin endTimestamp'i = uyanma zamanı
 *   3. sleepType:2 (derin uyku) segmentleri ortadan kalktıktan sonra
 *      gelen sleepType:1 (hafif uyku) da uyanmaya yaklaşıldığını gösterir
 */
object WakeDetector {

    private const val TAG = "WakeDetector"
    private const val PREFS_NAME = "wake_detector"
    private const val KEY_LAST_WAKE_TIME = "last_wake_time"
    private const val KEY_LAST_WAKE_DATE = "last_wake_date"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

    /**
     * Uyku kayıtlarından uyanma zamanını tespit eder.
     * @return Pair<wakeTime epoch millis, human-readable time string> veya null
     */
    fun detectWakeUp(records: List<SleepRecord>, prefs: SharedPreferences): Long? {
        if (records.isEmpty()) {
            Log.d(TAG, "Uyku kaydı yok")
            return null
        }

        // Son segmentin bitiş zamanı = uyanma zamanı
        val wakeTime = records.maxOf { it.endTimestamp }

        // Bugünün tarihi
        val today = dateFormat.format(Date())

        // Aynı gün için zaten uyanma tespit edildi mi kontrol et
        val lastWakeDate = prefs.getString(KEY_LAST_WAKE_DATE, "")
        if (lastWakeDate == today) {
            Log.d(TAG, "Bugün için uyanma zaten tespit edildi: $lastWakeDate")
            return null
        }

        // Uyanma zamanı geçmişte olmalı (bugün veya dün gece)
        val now = System.currentTimeMillis()
        if (wakeTime > now) {
            Log.d(TAG, "Uyanma zamanı gelecekte, bekleniyor: ${formatTime(wakeTime)}")
            return null
        }

        // Uyanma son 24 saat içinde olmalı
        val oneDayAgo = now - 24 * 60 * 60 * 1000
        if (wakeTime < oneDayAgo) {
            Log.d(TAG, "Uyanma zamanı 24 saatten eski: ${formatTime(wakeTime)}")
            return null
        }

        // Uyanma tespit edildi!
        Log.i(TAG, "UYANMA TESPİT EDİLDİ: ${formatTime(wakeTime)} " +
                "(${calculateConfidence(records)} güven)")

        // Kaydet
        prefs.edit()
            .putLong(KEY_LAST_WAKE_TIME, wakeTime)
            .putString(KEY_LAST_WAKE_DATE, today)
            .apply()

        return wakeTime
    }

    /**
     * Uyanma tespitinin güven skorunu hesaplar (0-100).
     */
    private fun calculateConfidence(records: List<SleepRecord>): Int {
        if (records.isEmpty()) return 0

        var score = 0

        // Yeterli uyku segmenti var mı?
        if (records.size >= 2) score += 20

        // Toplam uyku süresi makul mü (3-12 saat)?
        val totalMin = records.sumOf { it.sleepDuration }
        if (totalMin in 180..720) score += 30

        // Hem hafif hem derin uyku var mı?
        val hasLight = records.any { it.sleepType == 1 }
        val hasDeep = records.any { it.sleepType == 2 }
        if (hasLight && hasDeep) score += 30

        // Son segment hafif uyku mu? (normalde uyanmadan önce hafif uyku olur)
        if (records.lastOrNull()?.sleepType == 1) score += 20

        return score.coerceIn(0, 100)
    }

    /**
     * Uyanma zamanını insan-okunur formatta döndürür.
     */
    fun formatTime(epochMillis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
        return "${cal.get(Calendar.YEAR)}-${String.format("%02d", cal.get(Calendar.MONTH) + 1)}-${String.format("%02d", cal.get(Calendar.DAY_OF_MONTH))} ${String.format("%02d", cal.get(Calendar.HOUR_OF_DAY))}:${String.format("%02d", cal.get(Calendar.MINUTE))}"
    }

    /**
     * Kaydedilmiş son uyanma zamanını getirir.
     */
    fun getLastWakeTime(prefs: SharedPreferences): Long =
        prefs.getLong(KEY_LAST_WAKE_TIME, 0L)
}
