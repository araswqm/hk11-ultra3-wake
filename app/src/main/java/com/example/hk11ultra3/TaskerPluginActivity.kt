package com.example.hk11ultra3

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tasker/Locale plugin EditActivity.
 *
 * Tasker'ın "Plugin > HK11 Wake" altında göreceği aksiyon konfigürasyon ekranı.
 * Macrodroid de Locale plugin formatını destekler.
 *
 * İki aksiyon sunar:
 *   1. Senkronizasyonu başlat (zaten periyodik çalışıyordur, manuel tetikleme)
 *   2. Durum sorgula (son uyku verisini döndürür)
 */
class TaskerPluginActivity : Activity() {

    private var actionType = "sync"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mevcut intent'ten seçili aksiyonu oku (düzenleme modu)
        if (intent.hasExtra("action_type")) {
            actionType = intent.getStringExtra("action_type") ?: "sync"
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "HK11 Wake — Tasker Plugin"
            textSize = 18f
            setPadding(0, 0, 0, 24)
        }
        layout.addView(title)

        val desc = TextView(this).apply {
            text = """
                HK11 Ultra3 saat ile uyku takibi.

                Tetikleyici olarak kullanmak için:
                Tasker'da "Event > System > Intent Received" seçip
                action olarak aşağıdakilerden birini girin:

                • com.example.hk11ultra3.WAKE_DETECTED
                  → Uyanma tespit edildiğinde tetiklenir

                • com.example.hk11ultra3.SLEEP_UPDATED
                  → Uyku verisi güncellendiğinde tetiklenir

                Extras'lardan %wake_time_str,
                %total_sleep_minutes gibi değişkenleri okuyabilirsiniz.
            """.trimIndent()
            textSize = 13f
            setPadding(0, 0, 0, 24)
        }
        layout.addView(desc)

        // Sync başlat butonu
        val btnSync = Button(this).apply {
            text = "Senkronizasyon Başlat"
            setOnClickListener {
                actionType = "sync"
                returnResult("Senkronizasyon başlatıldı")
            }
        }
        layout.addView(btnSync)

        // Durum sorgula butonu
        val btnStatus = Button(this).apply {
            text = "Durum Sorgula (son uyku verisi)"
            setOnClickListener {
                actionType = "status"
                returnResult("Durum değişkenlere yazıldı")
            }
        }
        layout.addView(btnStatus)

        setContentView(layout)
    }

    private fun returnResult(blurb: String) {
        val prefs = getSharedPreferences("hk11_settings", MODE_PRIVATE)

        val resultIntent = Intent().apply {
            // Tasker'ın beklediği standart plugin sonuç formatı
            putExtra("com.twofortyfouram.locale.intent.extra.BLURB", blurb)
            putExtra("action_type", actionType)

            // Son uyku verisini değişken olarak ilet
            val wakeTime = prefs.getLong("wake_detector_last_wake_time", 0L)
            val sleepStart = prefs.getLong("summary_sleep_start", 0L)
            val totalMin = prefs.getInt("summary_total_min", 0)

            putExtra("wake_time_millis", wakeTime)
            putExtra("sleep_start_millis", sleepStart)
            putExtra("total_sleep_minutes", totalMin)

            if (wakeTime > 0) {
                val tf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                putExtra("wake_time_str", tf.format(java.util.Date(wakeTime)))
            }
            if (sleepStart > 0) {
                val tf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                putExtra("sleep_start_str", tf.format(java.util.Date(sleepStart)))
            }
        }

        // Tasker plugin için gerekli result intent
        setResult(RESULT_OK, resultIntent)

        // Başarılı mesajı ve kapat
        android.widget.Toast.makeText(this, "✓ $blurb", android.widget.Toast.LENGTH_SHORT).show()
        finish()
    }
}
