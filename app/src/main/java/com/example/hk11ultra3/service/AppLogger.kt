package com.example.hk11ultra3.service

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Basit uygulama ici logger. Loglari SharedPreferences'ta saklar,
 * UI'dan goruntulenebilir. Son 50 log tutulur.
 */
object AppLogger {

    private const val PREFS_KEY = "app_logs"
    private const val MAX_LINES = 50

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(context: Context, tag: String, message: String) {
        val prefs = context.getSharedPreferences("hk11_log", Context.MODE_PRIVATE)
        val lines = prefs.getString(PREFS_KEY, "") ?: ""
        val timestamp = timeFormat.format(Date())
        val entry = "$timestamp [$tag] $message"

        // Prepend new line, keep last MAX_LINES
        val allLines = (entry + "\n" + lines).lines().take(MAX_LINES)
        prefs.edit().putString(PREFS_KEY, allLines.joinToString("\n")).apply()

        // Also log to Android logcat
        android.util.Log.d(tag, message)
    }

    fun getLogs(context: Context): String {
        val prefs = context.getSharedPreferences("hk11_log", Context.MODE_PRIVATE)
        return prefs.getString(PREFS_KEY, "") ?: "(log bos)"
    }

    fun clear(context: Context) {
        context.getSharedPreferences("hk11_log", Context.MODE_PRIVATE)
            .edit().putString(PREFS_KEY, "").apply()
    }
}
