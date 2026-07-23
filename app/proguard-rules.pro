# HK11 Ultra3 Wake - ProGuard Rules

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# JSON
-keep class org.json.** { *; }

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

# App
-keep class com.example.hk11ultra3.** { *; }
