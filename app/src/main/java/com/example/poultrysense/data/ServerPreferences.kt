package com.example.poultrysense.data

import android.content.Context

object ServerPreferences {

    private const val PREFS_NAME = "poultrysense_server_prefs"
    private const val KEY_SERVER_IP = "server_ip"
    private const val DEFAULT_IP = "10.0.2.2" // Android emulator → host laptop
    const val PORT = "5050" // avoid macOS AirPlay on 5000

    /** Returns the full base URL, e.g. "http://192.168.1.50:5050" */
    fun getBaseUrl(context: Context): String {
        val ip = getIp(context)
        return "http://$ip:$PORT"
    }

    /** Returns just the saved IP address string */
    fun getIp(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_IP, DEFAULT_IP) ?: DEFAULT_IP
    }

    /**
     * Saves a host/IP. Accepts bare IP, hostname, or pasted URLs like
     * `http://192.168.1.50:5050` and normalizes to the host only.
     */
    fun saveIp(context: Context, raw: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SERVER_IP, normalizeHost(raw)).apply()
    }

    fun normalizeHost(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return value

        // Strip scheme
        value = value.removePrefix("http://").removePrefix("https://")

        // Strip path / query
        value = value.substringBefore('/').substringBefore('?')

        // Strip trailing port if present (IPv4/hostname only)
        if (value.count { it == ':' } == 1) {
            val host = value.substringBeforeLast(':')
            val port = value.substringAfterLast(':')
            if (port.all { it.isDigit() }) {
                value = host
            }
        }

        return value.trim().trimEnd('.')
    }
}
