package com.example.hk11ultra3

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.hk11ultra3.databinding.ActivityMainBinding
import com.example.hk11ultra3.service.AppLogger
import com.example.hk11ultra3.service.SyncScheduler
import com.example.hk11ultra3.service.WakeDetector
import com.example.hk11ultra3.service.WatchSyncService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("hk11_settings", MODE_PRIVATE) }

    private val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale("tr"))
    private val timeOnlyFormat = SimpleDateFormat("HH:mm", Locale("tr"))

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pollRunnable: Runnable? = null

    private fun startPolling() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        val startTime = System.currentTimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                val lastSync = prefs.getLong("summary_sync_time", 0L)
                val records = prefs.getInt("summary_segment_count", 0)
                val connected = prefs.getBoolean("ble_connected", false)

                if (lastSync > startTime) {
                    // Sync completed!
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = if (records > 0) "✅ $records uyku kaydi alindi ($elapsed saniye)"
                        else "⚠ Uyku kaydi bulunamadi"
                    updateStatusDisplay()
                    return
                }

                if (elapsed > 45) {
                    // Timeout
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = "❌ Sync zaman asimi (45sn)"
                    updateStatusDisplay()
                    return
                }

                binding.tvStatus.text = "⏳ Sync devam ediyor... ($elapsed sn)"
                handler.postDelayed(this, 2000)
            }
        }
        pollRunnable = runnable
        handler.postDelayed(runnable, 2000)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        requestPermissions()
        loadSettings()
    }

    override fun onResume() {
        super.onResume()
        updateStatusDisplay()
    }

    override fun onPause() {
        super.onPause()
        pollRunnable?.let { handler.removeCallbacks(it) }
    }

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "İzinler verildi", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "BLE ve bildirim izinleri gerekli!", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupUI() {
        // Sync butonu
        binding.btnSync.setOnClickListener {
            if (!hasPermissions()) {
                requestPermissions()
                return@setOnClickListener
            }
            saveSettings()

            // Bluetooth acik mi kontrol et
            val btManager = getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            val btAdapter = btManager.adapter
            if (btAdapter == null || !btAdapter.isEnabled) {
                AppLogger.log(this, "UI", "HATA: Bluetooth kapali")
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Bluetooth Kapali")
                    .setMessage("Senkronizasyon icin Bluetooth acik olmali. Acilsin mi?")
                    .setPositiveButton("Ac") { _, _ ->
                        val enableIntent = android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        startActivity(enableIntent)
                    }
                    .setNegativeButton("Iptal", null)
                    .show()
                return@setOnClickListener
            }

            binding.tvStatus.text = "⏳ Sync başlatılıyor..."
            binding.progressBar.visibility = View.VISIBLE
            prefs.edit().putLong("summary_sync_time", 0L).apply()
            WatchSyncService.startPeriodicSync(this)
            startPolling()
        }

        // Durdur butonu
        binding.btnStop.setOnClickListener {
            SyncScheduler.stopPeriodicSync(this)
            binding.tvStatus.text = "⏸ Durduruldu"
            binding.progressBar.visibility = View.GONE
            Toast.makeText(this, "Periyodik sync durduruldu", Toast.LENGTH_SHORT).show()
        }

        // Log goster butonu
        binding.btnShowLog.setOnClickListener {
            val logs = AppLogger.getLogs(this)
            if (binding.cardLog.visibility == View.VISIBLE) {
                binding.cardLog.visibility = View.GONE
                binding.btnShowLog.text = "📋 Loglari Goster"
            } else {
                binding.tvLog.text = logs
                binding.cardLog.visibility = View.VISIBLE
                binding.btnShowLog.text = "📋 Loglari Gizle"
            }
        }

        // Kaydet butonu
        binding.btnSave.setOnClickListener {
            saveSettings()
            Toast.makeText(this, "Ayarlar kaydedildi ✓", Toast.LENGTH_SHORT).show()
        }

        // Test webhook butonu
        binding.btnTestWebhook.setOnClickListener {
            saveSettings()
            val url = binding.etWebhookUrl.text.toString()
            if (url.isBlank()) {
                Toast.makeText(this, "Webhook URL gerekli", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.btnTestWebhook.isEnabled = false
            Thread {
                val sent = kotlinx.coroutines.runBlocking {
                    com.example.hk11ultra3.service.WebhookSender.send(
                        url = url,
                        wakeTime = System.currentTimeMillis(),
                        reminderTime = System.currentTimeMillis() + 7200_000,
                        totalSleepMinutes = 480
                    )
                }
                runOnUiThread {
                    binding.btnTestWebhook.isEnabled = true
                    Toast.makeText(
                        this@MainActivity,
                        if (sent) "Webhook gönderildi ✓" else "Webhook başarısız ✗",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.start()
        }
    }

    private fun loadSettings() {
        binding.etMacAddress.setText(prefs.getString("target_mac", ""))
        binding.etReminderDelay.setText(prefs.getInt("reminder_delay_minutes", 120).toString())
        binding.etWebhookUrl.setText(prefs.getString("webhook_url", ""))
        binding.swAutoSync.isChecked = prefs.getBoolean("auto_sync_on_boot", true)
    }

    private fun saveSettings() {
        prefs.edit()
            .putString("target_mac", binding.etMacAddress.text.toString().trim())
            .putInt(
                "reminder_delay_minutes",
                binding.etReminderDelay.text.toString().toIntOrNull() ?: 120
            )
            .putString("webhook_url", binding.etWebhookUrl.text.toString().trim())
            .putBoolean("auto_sync_on_boot", binding.swAutoSync.isChecked)
            .apply()
    }

    private fun updateStatusDisplay() {
        val lastWakeTime = WakeDetector.getLastWakeTime(prefs)
        if (lastWakeTime > 0) {
            val timeStr = dateFormat.format(Date(lastWakeTime))
            binding.tvWakeStatus.text = "Son uyanma: $timeStr"

            val delayMin = prefs.getInt("reminder_delay_minutes", 120)
            val reminderTime = lastWakeTime + (delayMin * 60_000L)
            val reminderStr = dateFormat.format(Date(reminderTime))
            binding.tvReminderStatus.text = "Hatırlatıcı: $reminderStr ($delayMin dk sonra)"
        } else {
            binding.tvWakeStatus.text = "Henüz uyanma tespit edilmedi"
            binding.tvReminderStatus.text = ""
        }

        binding.progressBar.visibility = View.GONE

        // Sync durumunu SharedPreferences'tan oku
        val lastSyncTime = prefs.getLong("summary_sync_time", 0L)
        val recordCount = prefs.getInt("summary_segment_count", 0)

        val lastError = prefs.getString("last_error", "") ?: ""
        val isConnected = prefs.getBoolean("ble_connected", false)

        binding.tvStatus.text = when {
            prefs.getString("target_mac", "")?.isBlank() == true -> "⚠ MAC adresi gerekli"
            lastSyncTime == 0L -> "⚡ Hazir (henuz sync yapilmadi)"
            recordCount > 0 -> "✅ $recordCount uyku kaydi alindi"
            isConnected && lastSyncTime < System.currentTimeMillis() - 60_000 -> "🔗 Baglandi ama veri yok (bekleniyor...)"
            else -> {
                val secondsAgo = (System.currentTimeMillis() - lastSyncTime) / 1000
                val err = if (lastError.isNotEmpty()) "\n$lastError" else ""
                "⚠ Uyku kaydi bulunamadi (${secondsAgo}s)$err"
            }
        }

        // Uyku özetini göster
        updateSleepSummary()
    }

    /**
     * Son uyku ozetini SharedPreferences'tan okuyup ekranda gosterir.
     */
    private fun updateSleepSummary() {
        val sleepStart = prefs.getLong("summary_sleep_start", 0L)
        val wakeTime = prefs.getLong("summary_wake_time", 0L)
        val totalMin = prefs.getInt("summary_total_min", 0)
        val deepMin = prefs.getInt("summary_deep_min", 0)
        val lightMin = prefs.getInt("summary_light_min", 0)
        val segments = prefs.getInt("summary_segment_count", 0)
        val sessionDate = prefs.getString("summary_date", "") ?: ""
        val syncTime = prefs.getLong("summary_sync_time", 0L)

        if (sleepStart == 0L && wakeTime == 0L) {
            binding.cardSleepSummary.visibility = View.GONE
            return
        }

        binding.cardSleepSummary.visibility = View.VISIBLE
        binding.tvSleepTitle.text = if (sessionDate.isNotEmpty()) "Son uyku: $sessionDate" else "Son uyku verisi"

        if (sleepStart > 0) {
            binding.tvSleepStart.text = timeOnlyFormat.format(Date(sleepStart))
        } else {
            binding.tvSleepStart.text = "--:--"
        }

        if (wakeTime > 0) {
            binding.tvSleepEnd.text = timeOnlyFormat.format(Date(wakeTime))
        } else {
            binding.tvSleepEnd.text = "henuz uyanmamis"
        }

        if (totalMin > 0) {
            val h = totalMin / 60
            val m = totalMin % 60
            binding.tvSleepTotal.text = "${h}s ${m}dk"
        } else {
            binding.tvSleepTotal.text = "--"
        }

        if (deepMin > 0 || lightMin > 0) {
            val dh = deepMin / 60
            val dm = deepMin % 60
            val lh = lightMin / 60
            val lm = lightMin % 60
            binding.tvSleepBreakdown.text = "${lh}s ${lm}dk / ${dh}s ${dm}dk"
        } else {
            binding.tvSleepBreakdown.text = "--"
        }

        if (segments > 0) {
            val syncStr = if (syncTime > 0) {
                " (son sync: ${timeOnlyFormat.format(Date(syncTime))})"
            } else ""
            binding.tvSleepSegments.text = "$segments segment$syncStr"
        } else {
            binding.tvSleepSegments.text = ""
        }
    }

    private fun requestPermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) needed.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                != PackageManager.PERMISSION_GRANTED
            ) needed.add(Manifest.permission.BLUETOOTH)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
                != PackageManager.PERMISSION_GRANTED
            ) needed.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) return false
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) return false
        }
        return true
    }
}
