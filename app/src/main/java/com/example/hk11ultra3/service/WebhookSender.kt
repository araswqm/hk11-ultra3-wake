package com.example.hk11ultra3.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Uyanma tespit edildiğinde webhook gönderir.
 *
 * Desteklenen formatlar:
 *   - Discord webhook
 *   - Slack webhook
 *   - Genel HTTP POST (JSON body)
 *   - ntfy.sh
 *   - Home Assistant webhook
 */
object WebhookSender {

    private const val TAG = "WebhookSender"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Webhook gönderir. URL'ye göre formatı otomatik tespit eder.
     *
     * @param url webhook URL'si
     * @param wakeTime uyanma zamanı (epoch millis)
     * @param reminderTime hatırlatıcı zamanı (epoch millis)
     * @param totalSleepMinutes toplam uyku süresi (dakika)
     */
    suspend fun send(
        url: String,
        wakeTime: Long,
        reminderTime: Long,
        totalSleepMinutes: Int
    ): Boolean {
        if (url.isBlank()) {
            Log.d(TAG, "Webhook URL boş, atlanıyor")
            return true
        }

        return withContext(Dispatchers.IO) {
            try {
                val wakeTimeStr = WakeDetector.formatTime(wakeTime)
                val reminderTimeStr = WakeDetector.formatTime(reminderTime)
                val sleepHours = totalSleepMinutes / 60
                val sleepMins = totalSleepMinutes % 60

                val body = when {
                    url.contains("discord.com/api/webhooks") -> buildDiscordBody(
                        wakeTimeStr, reminderTimeStr, sleepHours, sleepMins
                    )
                    url.contains("hooks.slack.com") -> buildSlackBody(
                        wakeTimeStr, reminderTimeStr, sleepHours, sleepMins
                    )
                    url.contains("ntfy.sh") -> buildNtfyBody(
                        wakeTimeStr, reminderTimeStr, sleepHours, sleepMins
                    )
                    else -> buildGenericBody(
                        wakeTime, reminderTime, totalSleepMinutes,
                        wakeTimeStr, reminderTimeStr
                    )
                }

                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody(jsonMediaType))
                    .build()

                val response = client.newCall(request).execute()
                Log.i(TAG, "Webhook gönderildi: ${response.code} — $url")

                response.isSuccessful
            } catch (e: Exception) {
                Log.e(TAG, "Webhook hatası", e)
                false
            }
        }
    }

    private fun buildDiscordBody(
        wakeTime: String, reminderTime: String,
        sleepHours: Int, sleepMins: Int
    ): String = JSONObject().apply {
        put("content", "⏰ **Uyanma Tespit Edildi!**")
        put("embeds", org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("title", "HK11 Ultra3 — Uyku Raporu")
                put("color", 0x4CAF50)
                put("fields", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("name", "🛏️ Uyanma Zamanı")
                        put("value", wakeTime)
                        put("inline", true)
                    })
                    put(JSONObject().apply {
                        put("name", "💊 İlaç Hatırlatıcı")
                        put("value", reminderTime)
                        put("inline", true)
                    })
                    put(JSONObject().apply {
                        put("name", "😴 Toplam Uyku")
                        put("value", "${sleepHours}s ${sleepMins}dk")
                        put("inline", true)
                    })
                })
            })
        })
    }.toString()

    private fun buildSlackBody(
        wakeTime: String, reminderTime: String,
        sleepHours: Int, sleepMins: Int
    ): String = JSONObject().apply {
        put("text", "⏰ Uyanma Tespit Edildi!")
        put("blocks", org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("type", "section")
                put("text", JSONObject().apply {
                    put("type", "mrkdwn")
                    put("text", """
                        *HK11 Ultra3 — Uyku Raporu*
                        🛏️ Uyanma: $wakeTime
                        💊 İlaç Hatırlatıcı: $reminderTime
                        😴 Toplam Uyku: ${sleepHours}s ${sleepMins}dk
                    """.trimIndent())
                })
            })
        })
    }.toString()

    private fun buildNtfyBody(
        wakeTime: String, reminderTime: String,
        sleepHours: Int, sleepMins: Int
    ): String = JSONObject().apply {
        put("topic", "hk11-wake")
        put("title", "Uyanma Tespit Edildi")
        put("message", "Uyanma: $wakeTime | İlaç: $reminderTime | Uyku: ${sleepHours}s ${sleepMins}dk")
        put("priority", 4)
        put("tags", org.json.JSONArray().apply {
            put("alarm")
            put("pill")
        })
    }.toString()

    private fun buildGenericBody(
        wakeTime: Long, reminderTime: Long, totalSleepMinutes: Int,
        wakeTimeStr: String, reminderTimeStr: String
    ): String = JSONObject().apply {
        put("event", "wake_up_detected")
        put("wake_time", wakeTime / 1000)          // unix timestamp
        put("wake_time_str", wakeTimeStr)
        put("reminder_time", reminderTime / 1000)
        put("reminder_time_str", reminderTimeStr)
        put("total_sleep_minutes", totalSleepMinutes)
        put("device", "HK11 Ultra3")
    }.toString()
}
