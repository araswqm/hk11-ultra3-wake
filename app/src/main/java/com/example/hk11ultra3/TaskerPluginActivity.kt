package com.example.hk11ultra3

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.hk11ultra3.service.WatchSyncService

class TaskerPluginActivity : Activity() {

    private val BLURB_KEY = "com.twofortyfouram.locale.intent.extra.BLURB"
    private val BUNDLE_KEY = "com.twofortyfouram.locale.intent.extra.BUNDLE"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If editing existing task, restore previous selection
        val prevBundle = intent.getBundleExtra(BUNDLE_KEY)
        val prevAction = prevBundle?.getString("action_type") ?: "sync"

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        layout.addView(TextView(this).apply {
            text = "HK11 Wake - Plugin"
            textSize = 18f
            setPadding(0, 0, 0, 24)
        })

        layout.addView(TextView(this).apply {
            text = "Choose an action. For Event triggers, use\nIntent Received with:\n• com.example.hk11ultra3.WAKE_DETECTED\n• com.example.hk11ultra3.SLEEP_UPDATED"
            textSize = 13f
            setPadding(0, 0, 0, 24)
        })

        layout.addView(Button(this).apply {
            text = "Start Sleep Sync"
            setOnClickListener {
                val data = Bundle().apply { putString("action_type", "sync") }
                returnResult(data, "Sleep sync started")
            }
        })

        layout.addView(Button(this).apply {
            text = "Query Sleep Status"
            setOnClickListener {
                val data = Bundle().apply { putString("action_type", "status") }
                returnResult(data, "Sleep data queried")
            }
        })

        scroll.addView(layout)
        setContentView(scroll)
    }

    private fun returnResult(bundle: Bundle, blurb: String) {
        // Add current sleep data to the result
        val prefs = getSharedPreferences("hk11_settings", MODE_PRIVATE)
        val wakeTime = prefs.getLong("wake_detector_last_wake_time", 0L)
        val sleepStart = prefs.getLong("summary_sleep_start", 0L)
        val totalMin = prefs.getInt("summary_total_min", 0)
        val deepMin = prefs.getInt("summary_deep_min", 0)
        val lightMin = prefs.getInt("summary_light_min", 0)
        val segments = prefs.getInt("summary_segment_count", 0)

        bundle.apply {
            putLong("wake_time_millis", wakeTime)
            putLong("sleep_start_millis", sleepStart)
            putInt("total_sleep_minutes", totalMin)
            putInt("deep_sleep_minutes", deepMin)
            putInt("light_sleep_minutes", lightMin)
            putInt("segment_count", segments)
            if (wakeTime > 0) {
                val tf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                putString("wake_time_str", tf.format(java.util.Date(wakeTime)))
            }
            if (sleepStart > 0) {
                val tf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                putString("sleep_start_str", tf.format(java.util.Date(sleepStart)))
            }
        }

        // Actually trigger the action for "sync" type
        if (bundle.getString("action_type") == "sync") {
            WatchSyncService.startPeriodicSync(this)
        }

        val resultIntent = android.content.Intent().apply {
            putExtra(BLURB_KEY, blurb)
            putExtra(BUNDLE_KEY, bundle)
        }
        setResult(RESULT_OK, resultIntent)
        android.widget.Toast.makeText(this, "✔ $blurb", android.widget.Toast.LENGTH_SHORT).show()
        finish()
    }
}
