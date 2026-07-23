package com.example.hk11ultra3.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log

/**
 * Cihaz açıldığında otomatik senkronizasyonu başlatır.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs: SharedPreferences = context.getSharedPreferences("hk11_settings", Context.MODE_PRIVATE)
        val autoSync = prefs.getBoolean("auto_sync_on_boot", true)

        if (!autoSync) {
            Log.d(TAG, "Otomatik sync kapalı")
            return
        }

        val mac = prefs.getString("target_mac", "") ?: ""
        if (mac.isBlank()) {
            Log.d(TAG, "MAC adresi ayarlanmamış")
            return
        }

        Log.i(TAG, "Boot tamamlandı, periyodik sync başlatılıyor")
        // Biraz bekle (BLE adaptör hazır olsun)
        Thread.sleep(5000)
        WatchSyncService.startPeriodicSync(context)
    }
}
