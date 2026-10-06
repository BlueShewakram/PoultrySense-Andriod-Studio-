package com.example.poultrysense.data

import android.content.Context
import android.content.SharedPreferences

object AppSettingsPreferences {

    private const val PREFS_NAME = "poultrysense_app_settings"

    // Notification Preferences
    private const val KEY_NOTIFICATIONS_ENABLED = "key_notifications_enabled"
    private const val KEY_CRITICAL_TEMP_ALERT = "key_critical_temp_alert"
    private const val KEY_HUMIDITY_ALERT = "key_humidity_alert"
    private const val KEY_CAMERA_DISCONNECT_ALERT = "key_camera_disconnect_alert"
    private const val KEY_DAILY_SUMMARY_ALERT = "key_daily_summary_alert"
    private const val KEY_ALERT_SOUND_ENABLED = "key_alert_sound_enabled"
    private const val KEY_ALERT_VIBRATE_ENABLED = "key_alert_vibrate_enabled"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ─── Notification Getters & Setters ───

    fun isNotificationsEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_NOTIFICATIONS_ENABLED, true)

    fun setNotificationsEnabled(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply()

    fun isCriticalTempAlertEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_CRITICAL_TEMP_ALERT, true)

    fun setCriticalTempAlertEnabled(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_CRITICAL_TEMP_ALERT, enabled).apply()

    fun isHumidityAlertEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_HUMIDITY_ALERT, true)

    fun setHumidityAlertEnabled(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_HUMIDITY_ALERT, enabled).apply()

    fun isCameraDisconnectAlertEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_CAMERA_DISCONNECT_ALERT, true)

    fun setCameraDisconnectAlertEnabled(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_CAMERA_DISCONNECT_ALERT, enabled).apply()

    fun isDailySummaryAlertEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_DAILY_SUMMARY_ALERT, true)

    fun setDailySummaryAlertEnabled(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_DAILY_SUMMARY_ALERT, enabled).apply()

    fun isAlertSoundEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_ALERT_SOUND_ENABLED, true)

    fun setAlertSoundEnabled(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_ALERT_SOUND_ENABLED, enabled).apply()

    fun isAlertVibrateEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_ALERT_VIBRATE_ENABLED, true)

    fun setAlertVibrateEnabled(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_ALERT_VIBRATE_ENABLED, enabled).apply()
}
