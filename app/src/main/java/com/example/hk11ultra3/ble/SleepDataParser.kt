package com.example.hk11ultra3.ble

import android.util.Log
import com.example.hk11ultra3.model.SleepRecord

/**
 * WearFit Pro'nun DataSyncProxy.saveSleep() metodundan reverse-engineer edilmiş
 * uyku verisi parser'ı.
 *
 * Paket formatı:
 *   datas[0]  = 0xAB (header)
 *   datas[4]  = 0x52 (CMD_SLEEP_DATA)
 *   datas[6]  = yıl - 2000
 *   datas[7]  = ay
 *   datas[8]  = gün
 *   datas[9]  = saat
 *   datas[10] = dakika
 *   datas[11] = sleepType (1=hafif, 2=derin)
 *   bytes[12..13] = sleepDuration (dakika, big-endian)
 */
object SleepDataParser {

    private const val TAG = "SleepDataParser"

    /**
     * Ham BLE paketinden SleepRecord parse eder.
     * @return SleepRecord veya null (paket uyku verisi değilse)
     */
    fun parse(bytes: ByteArray, datas: List<Int>): SleepRecord? {
        // Minimum kontrol: header + komut + yeterli veri
        if (datas.size < 13) return null
        if (datas[0] != 0xAB) return null
        if (datas[4] != BleProtocol.CMD_SLEEP_DATA) return null  // 0x52 = 82

        val year = datas[6] + 2000
        val month = datas[7]
        val day = datas[8]
        val hour = datas[9]
        val minute = datas[10]
        val sleepType = datas[11]
        val sleepDuration = BleProtocol.bytesToInt(bytes, 12, 2)

        val timestamp = BleProtocol.datasToTimestamp(datas)
        val endTimestamp = timestamp + (sleepDuration * 60_000L)

        Log.d(TAG, "Uyku kaydı: type=$sleepType duration=$sleepDuration dk " +
                "başlangıç=$hour:$minute bitiş=${(hour + (minute + sleepDuration) / 60) % 24}:${(minute + sleepDuration) % 60}")

        // Geçersiz değerleri filtrele
        if (sleepDuration <= 0 || sleepDuration >= 720) {
            Log.w(TAG, "Geçersiz uyku süresi: $sleepDuration dk, atlanıyor")
            return null
        }

        // Gelecek tarihli kayıtları filtrele
        if (timestamp > System.currentTimeMillis()) {
            Log.w(TAG, "Gelecek tarihli uyku kaydı, atlanıyor")
            return null
        }

        return SleepRecord(
            timestamp = timestamp,
            sleepType = sleepType,
            sleepDuration = sleepDuration,
            endTimestamp = endTimestamp
        )
    }

    /**
     * Günün tüm uyku kayıtlarından uyanma zamanını bulur.
     * Uyanma zamanı = son segmentin endTimestamp'i.
     */
    fun findWakeTime(records: List<SleepRecord>): Long? {
        if (records.isEmpty()) return null
        // Son segmentin bitiş zamanı
        return records.maxOfOrNull { it.endTimestamp }
    }

    /**
     * Bugüne ait uyku kayıtlarını filtreler.
     */
    fun filterToday(records: List<SleepRecord>): List<SleepRecord> {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis

        cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        val todayEnd = cal.timeInMillis

        return records.filter { it.timestamp in todayStart until todayEnd }
    }
}
